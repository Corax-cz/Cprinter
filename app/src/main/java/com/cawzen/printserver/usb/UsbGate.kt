package com.cawzen.printserver.usb

import java.util.concurrent.locks.ReentrantLock

/**
 * 全局 USB 写者锁。
 *
 * 为什么需要：现在有**两条**独立的打印入口 —— 局域网 IPP 服务（`JobStore`）
 * 和 Android 打印框架插件（`UsbPrintPlugin`）。它们跑在同一个进程里，但各自的
 * 队列互不知情。同一时刻只允许一个写者持有 Bulk OUT 端点，否则两股字节流会
 * 交错写进打印机，结果是一张乱码纸加一台状态机卡住的打印机。
 *
 * 用 [ReentrantLock] 而不是协程 `Mutex`：打印插件的回调是普通线程，不是协程，
 * 两边都要能拿同一把锁。阻塞等待在这里是可接受的 —— 打印本来就是长任务，
 * 等另一份文档打完比写出交错字节好得多。
 */
object UsbGate {

    @PublishedApi
    internal val lock = ReentrantLock()

    /** 当前是否有人在打印，供界面显示。 */
    val isBusy: Boolean get() = lock.isLocked

    inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
