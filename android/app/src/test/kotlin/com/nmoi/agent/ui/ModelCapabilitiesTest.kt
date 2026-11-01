package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 能力位三态（D208）：不知道 ≠ 不支持。 */
class ModelCapabilitiesTest {

    @Test
    fun `空列表是不知道-不是不支持`() {
        assertNull(ModelCapabilities.acceptsImages(emptyList()))
        assertNull(ModelCapabilities.acceptsImages(listOf("  ", "")))
    }

    @Test
    fun `写了 image 就是收`() {
        assertEquals(true, ModelCapabilities.acceptsImages(listOf("text", "image")))
        assertEquals(true, ModelCapabilities.acceptsImages(listOf("IMAGE")))
        assertEquals(true, ModelCapabilities.acceptsImages(listOf("vision")))
    }

    @Test
    fun `只写了 text 就是明确不收`() {
        assertEquals(false, ModelCapabilities.acceptsImages(listOf("text")))
        assertEquals(false, ModelCapabilities.acceptsImages(listOf("text", "audio")))
    }

    @Test
    fun `灰掉时那句话要点名它收什么`() {
        assertTrue(ModelCapabilities.refusal(listOf("text")).contains("text"))
        assertTrue(ModelCapabilities.refusal(emptyList()).contains("没有填写"))
    }
}
