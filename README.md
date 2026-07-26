# USB 备份 (UsbBackup)

一个类似 Hi Backup 的安卓应用，用于将手机内部存储的文件/文件夹备份到通过 OTG 连接的 U 盘中。

## 功能

- 选择内部存储中的任意文件夹作为备份源
- 选择 U 盘（外置 USB 存储）作为备份目标
- 增量备份：已存在且大小相同的文件自动跳过
- 保持原始目录结构
- 前台服务 + 通知栏进度显示，后台运行不中断
- 支持取消操作
- 自动记住上次的源/目标配置

## 使用方法

1. 用 Android Studio 打开本项目
2. Sync Gradle，连接手机运行
3. 通过 OTG 线将 U 盘插入手机
4. 打开 App：
   - 点击「选择文件夹」→ 选择要备份的目录（如 DCIM、Download 等）
   - 点击「选择U盘位置」→ 在文件选择器左侧找到 U 盘，选择目标文件夹
   - 点击「开始备份」

## 技术说明

| 组件 | 作用 |
|------|------|
| Storage Access Framework (SAF) | 读写 U 盘（Android 无法直接获取外置存储路径，必须通过 SAF） |
| DocumentFile API | 操作 SAF 返回的 tree Uri |
| Foreground Service | 保证大文件备份不被系统杀死 |
| WakeLock | 防止备份过程中 CPU 休眠 |
| ViewBinding | UI 绑定 |

## 最低要求

- Android 8.0 (API 26)
- 手机需支持 USB OTG
- U 盘格式建议 exFAT 或 FAT32

## 项目结构

```
app/src/main/java/com/example/usbbackup/
├── MainActivity.kt      # 主界面：选择源/目标、启动备份
├── BackupService.kt     # 前台服务：管理备份生命周期和通知
└── BackupEngine.kt      # 核心引擎：文件遍历、增量判断、流式复制
```

## 注意事项

- 首次选择 U 盘时系统会弹出授权对话框，点击「使用此文件夹」
- Android 11+ 对内部存储访问有限制，源文件夹选择器中部分系统目录可能不可见
- 如果 U 盘未被识别，请检查 OTG 连接和 U 盘格式
