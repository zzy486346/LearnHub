import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

async function loadApi(http = {}) {
  const source = (await readFile(new URL('../src/api/preferences.ts', import.meta.url), 'utf8'))
    .replace("import { http } from './http'", 'const http = globalThis.preferenceHttp')
  globalThis.preferenceHttp = http
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  return import(`data:text/javascript;base64,${Buffer.from(`${code}\n//${Math.random()}`).toString('base64')}`)
}
async function loadDialog(api, confirm = async () => {}) {
  const source = (await readFile(new URL('../src/components/PreferenceMemoryDialog.vue', import.meta.url), 'utf8'))
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const messages = []
  const harness = {
    preferenceApi: api, preferenceErrorMessage: () => '操作失败', preferenceCategoryLabels: {}, preferenceStatusLabel: value => value,
    ElMessage: { success: message => messages.push(message) }, ElMessageBox: { confirm },
    ref: value => ({ value }), computed: getter => ({ get value() { return getter() } }),
    defineProps: () => ({ modelValue: false }), defineEmits: () => () => {},
    watch: (getter, callback) => { harness.visibilityChanged = callback },
    onBeforeUnmount: () => {},
  }
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
  const bindings = Object.keys(harness)
  const dialog = new Function(...bindings, `${code}\nreturn {preferences,loading,busy,errorMessage,editing,text,category,save,forget,refresh,edit};`)(...bindings.map(key => harness[key]))
  return { ...dialog, messages, visibilityChanged: value => harness.visibilityChanged(value) }
}
const preference = { id: 'p1', version: 1, category: 'STYLE', text: '先举例', status: 'ACTIVE' }
const response = value => ({ data: { data: value } })

test('偏好API不发送用户身份，修改和忘记携带版本及编码后的ID', async () => {
  const calls = []
  const http = Object.fromEntries(['get', 'post', 'patch', 'delete'].map(method => [method, (...args) => calls.push([method, ...args])]))
  const { preferenceApi } = await loadApi(http)
  preferenceApi.list(); preferenceApi.create('STYLE', '先举例'); preferenceApi.update('p/1', 2, 'PACE', '每天学习'); preferenceApi.forget('p/1', 3)
  assert.deepEqual(calls, [
    ['get', '/assistant/memories'], ['post', '/assistant/memories', { category: 'STYLE', text: '先举例' }],
    ['patch', '/assistant/memories/p%2F1', { version: 2, category: 'PACE', text: '每天学习' }],
    ['delete', '/assistant/memories/p%2F1', { params: { version: 3 } }],
  ])
})
test('状态文案区分可用、待同步、远端待删除和未知状态', async () => {
  const { preferenceStatusLabel } = await loadApi()
  assert.match(preferenceStatusLabel('PENDING'), /暂不使用/)
  assert.match(preferenceStatusLabel('ACTIVE'), /可用于回答/)
  assert.match(preferenceStatusLabel('DELETING'), /待远端删除/)
  assert.match(preferenceStatusLabel('unknown'), /待确认/)
})
test('禁用和版本冲突有明确提示，未知错误不显示供应商原文', async () => {
  const { preferenceErrorMessage } = await loadApi()
  assert.match(preferenceErrorMessage({ response: { data: { code: 'MEMORY_DISABLED' } } }), /暂未启用/)
  assert.match(preferenceErrorMessage({ response: { data: { code: 'MEMORY_VERSION_CONFLICT' } } }), /刷新/)
  assert.match(preferenceErrorMessage({ code: 'ASSISTANT_AUTH_REFRESHED' }), /手动操作/)
  assert.match(preferenceErrorMessage({ response: { data: { code: 'MEMORY_LIMIT_EXCEEDED' } } }), /最多保存50条/)
  assert.doesNotMatch(preferenceErrorMessage(new Error('secret upstream body')), /secret|upstream/)
})
test('保存待同步偏好保留真实状态，不提示已可使用', async () => {
  const dialog = await loadDialog({ create: async () => response({ ...preference, status: 'PENDING' }) })
  dialog.text.value = '先举例'
  await dialog.save()
  assert.equal(dialog.preferences.value[0].status, 'PENDING')
  assert.match(dialog.messages[0], /等待同步/)
})
test('取消忘记不请求API，删除失败保留偏好并展示错误', async () => {
  let calls = 0
  const api = { forget: async () => { calls++; throw new Error('failure') } }
  const cancelled = await loadDialog(api, async () => { throw 'cancel' })
  await cancelled.forget(preference)
  assert.equal(calls, 0)
  const failed = await loadDialog(api)
  failed.preferences.value = [preference]
  await failed.forget(preference)
  assert.equal(calls, 1)
  assert.equal(failed.preferences.value.length, 1)
  assert.equal(failed.errorMessage.value, '操作失败')
})
test('远端待删除立即清除本地正文并说明补偿状态', async () => {
  const dialog = await loadDialog({ forget: async () => response({ ...preference, version: 2, text: '', status: 'DELETING' }) })
  dialog.preferences.value = [preference]
  await dialog.forget(preference)
  assert.equal(dialog.preferences.value[0].text, '')
  assert.equal(dialog.preferences.value[0].status, 'DELETING')
  assert.match(dialog.messages[0], /等待同步/)
})
test('关闭或换身份后丢弃迟到响应，不重现私人偏好', async () => {
  let finish
  const dialog = await loadDialog({ list: () => new Promise(resolve => { finish = resolve }) })
  const loading = dialog.refresh()
  dialog.visibilityChanged(false)
  finish(response([preference]))
  await loading
  assert.deepEqual(dialog.preferences.value, [])
  assert.equal(dialog.loading.value, false)
})
