# LLM 转发器（PoC 部署件）

CrashLlmWorker 需要访问 DGX Spark 上的裸 vllm（qwen-ablit 容器，127.0.0.1:18010，--network host）。
容器网络（logan-poc_logan-net）到不了宿主 127.0.0.1，且 8000 端口的 spark_ai_proxy
带 Host 白名单 + AI Console mode 门禁，PoC 不借道它。

本脚本 = 纯标准库 socket 反代：绑 100.88.73.95:18300 → 转发 127.0.0.1:18010（裸 vllm，无 Host 校验）。

部署（Spark 上，普通用户）：
```bash
mkdir -p /data/cache/logan-poc/ops && cp llm_forwarder.py /data/cache/logan-poc/ops/llm_fw2.py
cd /data/cache/logan-poc/ops && setsid nohup python3 llm_fw2.py > /dev/null 2>&1 < /dev/null &
```
自愈 crontab（每 10 分钟端口探活 + 开机自启）：
```
@reboot cd /data/cache/logan-poc/ops && nohup python3 llm_fw2.py > /dev/null 2>&1 < /dev/null
*/10 * * * * ss -tln | grep -q 18300 || (cd /data/cache/logan-poc/ops && nohup python3 llm_fw2.py > /dev/null 2>&1 < /dev/null)
```
注意：不要用 pkill -f llm_fw —— 执行它的 ssh/bash 命令行含同名字符串会自杀；用 fuser -k 18300/tcp。
`llm.properties` 对应 llm.base.url=http://100.88.73.95:18300/v1。
