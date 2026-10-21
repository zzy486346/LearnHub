# 混合检索语义门槛标定与验证

日期：2026-10-21。用户要求根据现有72题的资料充足/不足召回结果增加合理阈值。

## 实现结果

保留 RRF `k=60`，新增可配置的原始语义分数门槛 **0.815**。在融合后的候选上按同一 `chunkId + documentId + sourceVersion` 查对应语义分数，只有有限且处于 `[0.815, 1]` 的候选才进入 MySQL 正文及权限/版本复核。融合排序和 RRF 分数不重新计算，过滤掉第一名不会把第二名的分数抬高。

这里的语义分数使用当前 LangChain4j pgvector 的 `(1 + cosineSimilarity) / 2`，0.815 等价于余弦相似度约 0.63；不是81.5%的正确率，也不是 RRF 分数。已从本地 SDK 的实际计算实现核对，不能与原始余弦分数或 BM25 分数混用。

阈值只作用于正常 HYBRID 路径。纯 BM25、关闭向量、向量故障的 `BM25_FALLBACK` 保持原行为；因此降级时不保证同样的弱证据过滤效果。ES 故障仍抛不可用，不用可能不完整的向量投影代替完整检索。门槛策略自身的异常不被捕获成 BM25 降级。过滤前达到候选上限的标记继续保留。

原有 `1a0052f` 的证据不足结果及隐藏拒答弱来源逻辑没有被替换。此门槛仅是候选相关性粗筛，不能替代历史/工具证据验证、模型的资料充分性判断或最终答案验收。

## 为什么不直接给 RRF 分数设阈值

RRF 按名次累加 `1 / (60 + rank)`；双通道第一名最大值约0.032787。无关问题只要在两个列表中都排名靠前，也可以得到相同最高分。

本次重新进行本机只读采样，原始最高分分布如下：

| 题型 | 最高语义分数最小值 | 中位数 | 最大值 | 最高RRF分数范围 |
| --- | ---: | ---: | ---: | --- |
| 可回答48题 | 0.809327 | 0.856498 | 0.921442 | 0.032002～0.032787 |
| 资料不足12题 | 0.735499 | 0.790467 | 0.849745 | 0.031545～0.032787 |
| 跨课程12题 | 0.738887 | 0.768460 | 0.796206 | 0.031258～0.032787 |

RRF 分数高度重叠，直接截断容易把单通道强相关候选排除。语义分数也有重叠，因此不存在能把这72题完全分开的单一阈值。

## 选择0.815的依据与损失

保持融合排序，对全部候选使用语义门槛，取过滤后前5条：

| 门槛 | 可回答题来源命中@5 | 资料不足题仍有来源 | 跨课程题仍有来源 |
| --- | ---: | ---: | ---: |
| 0，原无门槛 | 48/48 | 12/12 | 12/12 |
| 0.800 | 48/48 | 6/12 | 0/12 |
| 0.809 | 48/48 | 4/12 | 0/12 |
| 0.815 | 47/48 | 1/12 | 0/12 |
| 0.820 | 46/48 | 1/12 | 0/12 |
| 0.850 | 27/48 | 0/12 | 0/12 |

0.814～0.817具有相同的查询级结果，选择中间的0.815作为本机模型初始门槛，避免仅卡某一个样本的小数边界。与0.8相比减少5道资料不足题的弱召回，但损失1道可回答题；比0.82保留更多正常来源。更高到0.85虽清空负例，却损失21道可回答题，不采用。

最终真实生产检索方法与原始分数回放逐题一致：

- 可回答来源命中47/48，约97.92%；来源MRR@5由约0.9583降至0.9375。未命中题为 `ans-12006-01`，属于 Redis 基础课程，不掩盖这次误过滤。
- 12道资料不足题仅1道仍有来源，累计弱来源由60降至2；剩余题为涉及 SCRIPT KILL 版本差异的 `none-8900835210-01`，最高语义分数约0.849745。
- 12道跨课程题均无来源；这是查询级弱召回变化，不是新增的跨用户安全结论。
- 没有调用付费生成模型，没有改动课程权限、资料正文、向量、Mem0或原首轮评测文件。

这些是已见72题上的**开发标定结果**，不是新独立留出成绩，也不是实际模型答案正确率。新问题或语料变化需另外评测；继续按用户问题调此阈值会增加过拟合风险。BM25独有而未得到向量门槛支持的候选也会被过滤，精确术语/编号类问题可能需要后续专门验证。

## 配置与使用

`.env.example` 已增加下列配置，本机 `.env` 已设置为0.815：

```dotenv
LEARNHUB_VECTOR_MIN_SEMANTIC_SCORE=0.815
```

启用混合检索仍需 `vector` profile、`LEARNHUB_VECTOR_ENABLED=true` 和 `LEARNHUB_VECTOR_HYBRID_ENABLED=true`。该值由 `application-vector.yml` 映射到 `learnhub.vector.min-semantic-score`，不在 `[0,1]` 内或非有限数值会拒绝创建服务。设为0可关闭本门槛，复现无门槛基线。

代理没有重启用户后端。用户下次正常重启后生效；不需要重新向量回填。原向量回填/较早基础设施留出测试显式设门槛0，以复现当时无门槛成绩；新门槛由独立只读标定测试验证，不能用开启门槛后的成绩覆盖旧报告。

## 数据与复跑

- [原始三路分数](assistant-retrieval-threshold-scores-20261021.json)：72题、719个原始语义候选分数，不含用户编号、正文、密钥或签名URL。
- [标定与逐题摘要](assistant-retrieval-threshold-calibration-20261021.json)：原始分数SHA256、分布、扫描结果、误过滤ID、真实路径与回放一致标记。
- `.local/retrieval-threshold-validation-20261021.json`：本机完整真实路径过滤结果，不提交。

离线复跑（不访问服务）：

```powershell
python scripts/assistant/test_retrieval_threshold.py
python scripts/assistant/analyze-retrieval-threshold.py --input docs/assistant-retrieval-threshold-scores-20261021.json --output .local/retrieval-threshold-replay.json
```

显式本机只读验证（不启动HTTP服务、不回填、不调用生成模型）：

```powershell
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' -pl learnhub-server -am '-Dtest=LocalRetrievalThresholdCalibrationIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.retrieval.threshold.calibrate=true' test
python scripts/assistant/analyze-retrieval-threshold.py --input docs/assistant-retrieval-threshold-scores-20261021.json --validation .local/retrieval-threshold-validation-20261021.json --output .local/retrieval-threshold-verified-replay.json
```

维护测试复用本机数据库地址守卫，移除全部调度基础设施并屏蔽业务启动迁移。评测使用现有可读用户，预期课时必须有授权。普通构建跳过此测试，避免意外访问开发服务。

## 分支说明

接手时工作区干净，但当前分支已是 `master`，代码基线为 `2f25ab8`，不是旧交接中的分支。最初只读采集测试 `67b3835` 提交在该分支；后续阈值功能转到 `feature/assistant-retrieval-threshold`，没有合并回master，也没有重写用户分支历史。

## 验证注意事项

专项覆盖语义边界、非有限及越界分数、门槛关闭、BM25独有候选、语义独有候选、融合排名保留、候选上限保留、纯BM25/向量异常降级、退款及资料换版、投影身份冲突和非法配置。Python回放覆盖精确边界、异常分数和0关闭门槛，3项通过。

第一次全量test通过；随后verify受到审查代理误启动的第二个Maven构建干扰，出现大量暂时性ClassNotFound。冲突构建已按归属停止，最终验收改为串行执行。不要把这次受干扰结果算作阈值行为失败，也不要把未通过的verify省略。

最终串行根 `mvn test` 和 `mvn verify` 均退出0；最新完整报告统计612项、498实际执行、114条件跳过，失败/错误0。混合检索专项14项通过，Python回放3项通过，真实本机72题已验证生产路径与离线回放一致。静态审查无剩余阻断。没有保留临时HTTP后端或追加供应商调用，旧首轮成绩不变。
