package com.devmind.common.decision;

/**
 * CAP-56 FR-03/FR-05 一次评测/微调执行的<b>执行包</b>：脚本 + 数据打包成一个 zip 字节流。
 *
 * <p>为什么不随 exec 帧传输：exec 帧是 WS 上的 JSON（{@code AgentExecCommand}），塞几百 KB 的
 * 数据集会把每一帧的编解码都拖慢，而节点侧真正需要的"文件"这件事帧里本来就表达不了。
 * 所以照 CAP-34 上下文包（{@link com.devmind.common.agent.ContextPackage}）的先例：
 * 帧里只放<b>引用</b>（{@code kind} + {@code id}），字节由 runner 凭节点 token 走 HTTP 拉取，
 * 拉不到即 exec 失败（不降级跑一个没有脚本的步骤）。</p>
 *
 * <p>包内固定含一份 {@code bundle.json} 清单（入口文件名 / 数据文件名，见
 * {@link LabBundles#MANIFEST_NAME}）——<b>清单放在包里而不是帧里</b>：帧里放一份、包里放一份的话，
 * 两次构建之间任何差异都会让 runner 去跑一个包里并不存在的入口文件，而那种错在日志里长得像
 * "脚本自己报错"。包里带着清单，就只有一份事实。</p>
 *
 * @param fileName 包名（仅用于日志与临时文件名，如 {@code laya-eval-7.zip}）
 * @param zip      {@link LabBundles#pack} 产出的 zip 字节
 */
public record LabBundle(String fileName, byte[] zip) {
}
