# Simple-Minecraft-Launcher-SCL-
📦 可用命令列表:
  ##install minecraft <version>                 - 下载指定版本游戏
  install <loader> <version>                  - 安装加载器 (fabric/forge/neoforge/quilt)
  install server <type> <version> <max> <min> - 下载服务器 (spigot/paper/purpur/fabric/forge)
  install java-<version>                      - 下载指定版本 Java (如 17,21)
  install <mod1, mod2,...>                    - 批量安装模组（逗号分隔）
  install shaderpack <name>                   - 搜索并下载光影包到当前版本
  import <path>                               - 导入本地整合包 (.zip/.mrpack)
  launch <version>                            - 启动游戏
  login                                       - 登录微软账号
  logout                                      - 退出当前登录
  player-name=<name>                          - 设置离线玩家名
  list loaders                                - 查看支持的加载器
  frp config / start / stop / status          - 管理 frp 内网穿透
  about                                       - 鸣谢人员名单
  clean                                       - 清除控制台信息
  close                                       - 关闭启动器
  list mods                                   - 列出当前版本模组及更新状态
  mod update                                  - 检查模组更新
  mod disable <mod-name>                      - 禁用模组
  mod enable <mod-name>                       - 启用模组
  server console <type> [nogui]               - 启动服务器
  server config <type>                        - 编辑服务器配置文件
  help / h                                    - 显示此列表
