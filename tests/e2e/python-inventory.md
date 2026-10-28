# Python 测试迁移清单

共 66 个归档 Python 脚本（tests/legacy/**）。由 python_inventory.py 生成；只静态读取，不执行脚本。
信号仅帮助审核，不代表已验证的前置或安全结论。legacy 不能直接批量导入可执行套件。

| 脚本 | 状态 | 静态信号 | 替代/审核说明 |
|---|---|---|---|
| [cap03_verify.py](../legacy/api/cap03_verify.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap06_verify.py](../legacy/api/cap06_verify.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap101_verify.py](../legacy/api/cap101_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap10_acceptance_e2e.py](../legacy/api/cap10_acceptance_e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap11_verify.py](../legacy/api/cap11_verify.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap21-project-default-node.py](../legacy/api/cap21-project-default-node.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap23_verify.py](../legacy/api/cap23_verify.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap28_e2e.py](../legacy/api/cap28_e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap29_e2e.py](../legacy/api/cap29_e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap32_e2e.py](../legacy/api/cap32_e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap33_verify.py](../legacy/api/cap33_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap34_fr04_08_verify.py](../legacy/api/cap34_fr04_08_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap34_reattach_verify.py](../legacy/api/cap34_reattach_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap40_e2e.py](../legacy/api/cap40_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap44_verify.py](../legacy/api/cap44_verify.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap45_verify.py](../legacy/api/cap45_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap46_verify.py](../legacy/api/cap46_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap47_verify.py](../legacy/api/cap47_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap48_verify.py](../legacy/api/cap48_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap49_verify.py](../legacy/api/cap49_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap52_e2e.py](../legacy/api/cap52_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap54_e2e.py](../legacy/api/cap54_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap55_records_verify.py](../legacy/api/cap55_records_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap55_triage_verify.py](../legacy/api/cap55_triage_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap55_verify.py](../legacy/api/cap55_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap56_224_verify.py](../legacy/api/cap56_224_verify.py) | legacy | 含写请求（可能仅登录） | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap56_e2e.py](../legacy/api/cap56_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap56_gpu_e2e.py](../legacy/api/cap56_gpu_e2e.py) | legacy | 含写请求（可能仅登录）、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap56_sidecar_source.py](../legacy/api/cap56_sidecar_source.py) | legacy | 创建进程 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap57_classify_e2e.py](../legacy/api/cap57_classify_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap58_e2e.py](../legacy/api/cap58_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap59_e2e.py](../legacy/api/cap59_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap64_e2e.py](../legacy/api/cap64_e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap65_e2e.py](../legacy/api/cap65_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap67_usage_stats_e2e.py](../legacy/api/cap67_usage_stats_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap68_e2e.py](../legacy/api/cap68_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap69_e2e.py](../legacy/api/cap69_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap70_e2e.py](../legacy/api/cap70_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap71_e2e.py](../legacy/api/cap71_e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap72_e2e.py](../legacy/api/cap72_e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap75_bot_verify.py](../legacy/api/cap75_bot_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap76_copilot_verify.py](../legacy/api/cap76_copilot_verify.py) | legacy | 含写请求（可能仅登录）、创建进程 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap76_tools_contract.py](../legacy/api/cap76_tools_contract.py) | migrated | 需人工审核 | contract.copilot.*; tests/legacy/api wrapper delegates to standard runner |
| [cap78_feishu_flow_verify.py](../legacy/api/cap78_feishu_flow_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、runner | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap80_runner_verify.py](../legacy/api/cap80_runner_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、runner | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap80_verify.py](../legacy/api/cap80_verify.py) | legacy | 含写请求（可能仅登录）、创建进程 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap83_verify.py](../legacy/api/cap83_verify.py) | legacy | 含写请求（可能仅登录）、创建进程、runner | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap87_changelog_e2e.py](../legacy/api/cap87_changelog_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap88_e2e.py](../legacy/api/cap88_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap89_e2e.py](../legacy/api/cap89_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap90_e2e.py](../legacy/api/cap90_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap91_goals_e2e.py](../legacy/api/cap91_goals_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap92_e2e.py](../legacy/api/cap92_e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [cap93_e2e.py](../legacy/api/cap93_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [e2e-agent-node.py](../legacy/api/e2e-agent-node.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [e2e-cap41.py](../legacy/api/e2e-cap41.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [e2e-cap41d-autofinish.py](../legacy/api/e2e-cap41d-autofinish.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [e2e-cap42.py](../legacy/api/e2e-cap42.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [e2e-cap74-m3.py](../legacy/api/e2e-cap74-m3.py) | legacy | 含写请求（可能仅登录）、创建进程、runner | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [e2e-cap74.py](../legacy/api/e2e-cap74.py) | legacy | 含写请求（可能仅登录）、创建进程、runner | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [e2e-events-pagination.py](../legacy/api/e2e-events-pagination.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [e2e-git-import-range.py](../legacy/api/e2e-git-import-range.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [e2e-worklog-keyword.py](../legacy/api/e2e-worklog-keyword.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [requirement_attachments_e2e.py](../legacy/api/requirement_attachments_e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [skill-import-e2e.py](../legacy/api/skill-import-e2e.py) | legacy | 含写请求（可能仅登录）、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
| [usage_records_e2e.py](../legacy/api/usage_records_e2e.py) | legacy | 含写请求（可能仅登录）、创建进程、runner、含本机默认地址 | 待核对环境、鉴权、清理和断言后迁入正式套件；禁止批量执行 |
