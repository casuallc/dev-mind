# 仓库协作约定

涉及 E2E 的新增、修改、验收或平台接入时，必须先读 `docs/core/e2e-testing-standard.md` 和 `tests/README.md`。
统一入口是 `python tests/e2e/run.py`，新增正式用例必须登记 `tests/e2e/catalog.json`；不得把未审核的历史脚本直接加入共享环境套件。
共享实例只执行 smoke；写入回归使用入口自起的隔离实例。日志、报告、测试数据全部落 `tmp/`。
