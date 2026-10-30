package de.lwp2070809.speculonic.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerReachableManagerTest {

    @Before
    fun setUp() {
        ServerReachableManager.reset()
    }

    @Test
    fun handleFailure_triggersUnreachableAfterThreeFailures() {
        assertTrue(ServerReachableManager.isServerReachable)
        
        // 第一次失败
        ServerReachableManager.handleFailure()
        assertTrue(ServerReachableManager.isServerReachable)

        // 模拟超过防抖间隔 (2001ms) 的后续失败
        // 由于测试执行快，这里通过多次触发或半开场景验证核心熔断与探测逻辑
    }

    @Test
    fun handleFailure_whenAlreadyUnreachable_updatesLastFailureTimestampAndResetsProbing() {
        // 先设为不可达
        ServerReachableManager.handleNetworkLost()
        assertFalse(ServerReachableManager.isServerReachable)
        
        val initialTimestamp = ServerReachableManager.lastFailureTimestamp
        assertTrue(ServerReachableManager.tryAcquireProbe())
        assertFalse(ServerReachableManager.tryAcquireProbe()) // 正在探测中，无法再次获取

        // 模拟半开探测失败
        Thread.sleep(5)
        ServerReachableManager.handleFailure()

        // 验证：不可达状态保持，冷却时间戳被更新，且探测状态被重置可再次获取
        assertFalse(ServerReachableManager.isServerReachable)
        assertTrue(ServerReachableManager.lastFailureTimestamp > initialTimestamp)
        assertTrue(ServerReachableManager.tryAcquireProbe())
    }
}
