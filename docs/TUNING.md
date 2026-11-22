# 调参与验收

`GuardConfig` 里的默认值是**起始值，不是标定值**。前摄视场角、传感器尺寸、自动曝光行为
在不同机型上差异很大，必须在目标机型上重新标定。本文给出方法。

## 0. 先定验收指标

先用业务方认可的数字锁定目标，再动参数。建议：

| 指标 | 建议目标 | 测量方法 |
|---|---|---|
| 误遮挡率 | < 1 次/小时 | 单人正常使用 30 分钟后统计 PARTIAL/FULL 次数 |
| 检出延迟（正面/侧面） | ≤ 1 s | 录制"第二人靠近并看屏幕"的视频，逐帧标注 |
| 检出率 | ≥ 95% | 同一场景 20 次 |
| 额外耗电 | < 5% / 30 min | `dumpsys batterystats` 或 Perfetto |

## 1. 误遮挡调试流程

命中误遮挡时，先判断属于哪一类：

```
adb logcat -s PrivacyShield                     # 只看关键日志
adb shell run-as com.acme.privacyshield \
    cat files/shield_events.jsonl | tail -50     # 审计日志中的 reason
```

按 `reason` 字段分流：

- `BYSTANDER` 但现场只有本人 → 主人判定失败。检查是否**换了座位/换了持机姿势**，
  导致新轨迹落在主人区域外。调大 `ownerZoneTolerance` 或 `ownerReacquireWindowMs`。
- `BYSTANDER` 且现场确实有路人但没在看 → 放宽 `gazeMaxYawDegrees` 下调
  （即更严格的"朝屏幕"判定）或调大 `bystanderOnboardMs`。
- `LOW_VISIBILITY` → 现场太暗/逆光/地铁隧道。检查 `lowLightMean` 是否过低；
  确认用户点"继续查看"是否能恢复。若频繁出现，说明该场景本就无法可靠检测，应保持 PARTIAL。
- `CAMERA_OBSTRUCTED` → 检查是否手指挡镜头、镜头脏污、或放在口袋/包里。
- `LENS_AIMED_AT_SCREEN` → 镜面反光误判。**此特性默认关闭**，非必要不开。

## 2. 参数速查

| 现象 | 调整 |
|---|---|
| 遮挡太慢 | 降 `bystanderOnboardMs`（或提高灵敏度滑块） |
| 遮挡太频繁 | 升 `bystanderOnboardMs`、降 `gazeMaxYawDegrees`、降 `minReadableFaceArea` |
| 解除太慢，影响干活 | 降 `releaseMs`（但会增加闪烁） |
| 转头回来就要验证密码 | 升 `ownerReacquireWindowMs`（**需安全团队同意**） |
| 地铁隧道里频繁闪 | 升 `unstableLumaDelta` / `unstableSuppressMs` |
| 暗处一直报 LOW_VISIBILITY | 升 `lowLightMean` 判定阈值或降为 PARTIAL→NONE |

> `ownerReacquireWindowMs` 是安全/体验的主要杠杆，任何变更都需安全团队书面确认。

## 3. 现场采集建议

- 至少 4 类环境：办公室（均匀白光）、地铁（晃动 + 隧道明暗交替）、咖啡馆（侧窗强逆光）、
  夜间出租/网约车（极暗 + 路灯扫过）。
- 每类至少 2 名不同身高/发型的员工，含戴眼镜、戴口罩、戴帽子各一组。
- 双人场景：同排、斜后方 45°、正后方（前摄看不到，记录为已知盲区）。

## 4. 每次改参数的回归

```bash
./gradlew :app:testDebugUnitTest          # 23 个行为测试必须全过
./gradlew :app:assembleDebug
./scripts/verify_privacy_guarantees.sh    # 隐私门禁
```

行为测试是**契约**：如果某个默认值改到让测试失败，说明你改动了隐私/体验的权衡，
需要在上表中说明理由，而不是去改测试。
