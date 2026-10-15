import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { prepareDocuments } from './prepare-documents.mjs'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const scriptPath = join(scriptDirectory, 'prepare-documents.mjs')
const catalogPath = resolve(scriptDirectory, '..', 'course-media', 'catalog.json')

function temporaryDirectory() {
  return mkdtempSync(join(tmpdir(), 'learnhub-knowledge-prepare-'))
}

function cleanup(directory) {
  const target = resolve(directory)
  assert.ok(target.startsWith(resolve(tmpdir()) + sep))
  assert.ok(basename(target).startsWith('learnhub-knowledge-prepare-'))
  rmSync(target, { recursive: true, force: true })
}

function mappingFile(directory, value) {
  const path = join(directory, 'mapping.json')
  writeFileSync(path, JSON.stringify(value), 'utf8')
  return path
}

const validMappings = {
  reviewed: true,
  mappings: [
    {
      courseSlug: 'java-basics',
      lessonTitle: '变量和方法怎样配合',
      courseId: '9007199254740993',
      lessonId: '9007199254741001',
      sourceKey: 'catalog.java-basics.variables-methods',
    },
    {
      courseSlug: 'java-concurrency',
      lessonTitle: '线程池如何承接异步任务',
      courseId: '9007199254740994',
      lessonId: '9007199254741002',
      sourceKey: 'catalog.java-concurrency.thread-pool',
    },
  ],
}

test('真实 catalog 的两门课通过显式映射生成管理员导入 payload', () => {
  const directory = temporaryDirectory()
  try {
    const stdout = execFileSync(process.execPath, [scriptPath, '--mapping', mappingFile(directory, validMappings)], {
      encoding: 'utf8',
    })
    const payloads = JSON.parse(stdout)
    assert.equal(payloads.length, 2)
    assert.deepEqual(payloads.map(({ courseId, lessonId }) => [courseId, lessonId]), [
      ['9007199254740993', '9007199254741001'],
      ['9007199254740994', '9007199254741002'],
    ])
    assert.equal(payloads[0].title, '变量和方法怎样配合')
    assert.equal(payloads[0].scenes[0].heading, '订单输入')
    assert.match(payloads[0].scenes[0].content, /收银台传来单价/)
    assert.doesNotMatch(payloads[0].scenes[0].content, /^价格和数量先用明确类型保存/)
    assert.equal(payloads[1].sourceKey, 'catalog.java-concurrency.thread-pool')
  } finally {
    cleanup(directory)
  }
})

test('narration 为空时回退 body，示例映射保留空 ID 且默认未核验', () => {
  const payloads = prepareDocuments([{
    slug: 'fixture-course',
    lessons: [{
      title: 'fixture-lesson',
      scenes: [{ heading: ' 标题 ', narration: ' ', body: ' 后备正文 ' }],
    }],
  }], {
    reviewed: true,
    mappings: [{
      courseSlug: 'fixture-course',
      lessonTitle: 'fixture-lesson',
      courseId: '1',
      lessonId: '2',
      sourceKey: 'fixture.source',
    }],
  })
  assert.deepEqual(payloads[0].scenes, [{ heading: '标题', content: '后备正文' }])

  const example = JSON.parse(readFileSync(join(scriptDirectory, 'mapping.example.json'), 'utf8'))
  assert.equal(example.reviewed, false)
  assert.equal(example.mappings[0].courseId, '')
  assert.equal(example.mappings[0].lessonId, '')
})

test('未人工核验、非法 long ID、缺失匹配和重复映射都被拒绝且 stdout 为空', async (t) => {
  const cases = [
    ['未核验', { ...validMappings, reviewed: false }],
    ['数字ID', {
      ...validMappings,
      mappings: [{ ...validMappings.mappings[0], courseId: 123 }],
    }],
    ['越界ID', {
      ...validMappings,
      mappings: [{ ...validMappings.mappings[0], lessonId: '9223372036854775808' }],
    }],
    ['课时不存在', {
      ...validMappings,
      mappings: [{ ...validMappings.mappings[0], lessonTitle: '不存在的课时' }],
    }],
    ['重复映射', {
      ...validMappings,
      mappings: [validMappings.mappings[0], { ...validMappings.mappings[0], sourceKey: 'another-source' }],
    }],
  ]

  for (const [name, mapping] of cases) {
    await t.test(name, () => {
      const directory = temporaryDirectory()
      try {
        const result = spawnSync(process.execPath, [
          scriptPath,
          '--catalog', catalogPath,
          '--mapping', mappingFile(directory, mapping),
        ], { encoding: 'utf8' })
        assert.notEqual(result.status, 0)
        assert.equal(result.stdout, '')
        assert.notEqual(result.stderr.trim(), '')
      } finally {
        cleanup(directory)
      }
    })
  }
})
