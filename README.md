# 防窥盾 (Privacy Shield)

面向银行/券商员工的安卓办公应用防窥模块：用前置摄像头在设备本地判断屏幕前是否只有员工本人，
有人偷看时自动遮挡客户资料与交易数据。

**摄像头画面不存储、不上传；应用没有 INTERNET 权限。**

---

## 快速开始

```bash
# 需要 JDK 17 与 Android SDK (platform 34, build-tools 35)
echo "sdk.dir=$HOME/android-sdk" > local.properties

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./scripts/verify_privacy_guarantees.sh

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

打开 App → "打开客户资料（受保护页面）" 即进入防窥场景。

## 目录

```
app/src/main/java/com/acme/privacyshield/
  core/engine/     ← 纯 Kotlin 决策状态机（可 JVM 单测，无 Android 依赖）
  core/model/      ← 观测数据结构（只有数字/矩形，不含图像）
  core/light/      ← 实验性镜头反光启发式（默认关闭）
  camera/          ← CameraX + ML Kit 管线；亮度采样；生命周期/省电宿主
  ui/              ← 受保护页面与设置页
  data/            ← 非图像设置、纯元数据审计日志
app/src/test/…     ← 23 个行为测试（覆盖误报/漏报/光照/生命周期/功耗调度）
docs/DESIGN.md     ← 架构、决策取舍、残差风险、隐私保证
docs/TUNING.md     ← 参数标定与验收方法
scripts/verify_privacy_guarantees.sh  ← CI 隐私门禁
```

## 三态遮挡

| 等级 | 触发 | 表现 |
|---|---|---|
| `NONE` | 只有本人 | 正常显示 |
| `PARTIAL` | 持续暗光/过曝，无法确认 | 敏感字段**置空**，顶部提示条 |
| `FULL` | 检测到他人、镜头被遮挡、超时未识别到本人 | 不透明遮挡层 |

`FULL` 仅在"镜头被遮挡（用户自己的手）"或"通过设备指纹/PIN 验证身份"时才能提前解除；
检测到**他人**时没有任何一键解除入口。

## ⚠️ 已知限制（务必阅读 `docs/DESIGN.md` §1 §4）

- 防不住远距离/长焦**拍照**——这靠 `FLAG_SECURE`、水印与 MDM，不靠摄像头。
- "员工离开又回来" 与 "陌生人捡起手机" 在纯摄像头下不可区分；超过 5 s 一律要求**设备凭据**恢复。
- 默认参数是起点，需按真机与目标机型重新标定（见 `docs/TUNING.md`）。
- 这是**降低偶发泄露概率**的功能，不是访问控制；不要对外宣称"杜绝泄露"。
