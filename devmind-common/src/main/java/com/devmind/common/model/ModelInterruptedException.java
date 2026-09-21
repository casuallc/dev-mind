package com.devmind.common.model;

/**
 * CAP-49 对话流式调用<b>被中断</b>（用户点「停止生成」）：与"网络/端点失败"必须分开报。
 *
 * <p>继承 {@link ModelCallException} 是为了让"只关心调用失败"的调用方 catch 一个类型就够；而需要区分的
 * 调用方（模型会话运行时）先 catch 本类——中断是<b>正常收尾</b>，要保留已产出的部分正文并让会话回到
 * 可继续提问的状态，不是 {@code FAILED}。</p>
 */
public class ModelInterruptedException extends ModelCallException {

    public ModelInterruptedException(String message) {
        super(message);
    }
}
