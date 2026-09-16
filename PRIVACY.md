<!--
SPDX-FileCopyrightText: 2026 guyii
SPDX-License-Identifier: GPL-3.0-or-later
-->

# 隐私

**这个输入法不联网，也没有联网的能力。**

`INTERNET` 与 `ACCESS_NETWORK_STATE` 两项权限以 `tools:node="remove"` 从合并清单中移除，
任何第三方依赖都无法把它们加回来。这不是一句承诺，是可以自己验证的：

```bash
aapt2 dump badging guyii-ime.apk | grep uses-permission
```

输出里不会有 INTERNET。因此你输入的任何内容 —— 包括词频、用户词典、剪贴板 ——
在物理上都不可能离开这台手机。

## 申请了哪些权限，为什么

| 权限 | 用途 |
|---|---|
| `VIBRATE` | 按键震动反馈 |
| `POST_NOTIFICATIONS` | 部署词库等耗时操作完成时提示 |
| `RECEIVE_BOOT_COMPLETED` | 开机后输入法可直接使用，无需先打开一次应用 |
| `WAKE_LOCK` / `FOREGROUND_SERVICE` | 部署词库期间避免被系统中断 |

**没有**申请存储、通讯录、位置、相机、麦克风等任何权限。诊断日志写入
`Download/` 走的是 MediaStore，这条路径本身就不需要存储权限。

## 数据存在哪

全部在应用自己的目录内：词库、用户词典、输入习惯（词频调整）、剪贴板历史。
卸载应用即全部删除。没有任何同步、备份或上报机制。
