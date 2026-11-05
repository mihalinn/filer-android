package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test
    fun 初めて使う人にだけ出す() {
        assertTrue(Onboarding.shouldShow(onboarded = false, hasAccess = false))
    }

    @Test
    fun すでに使っている人には出さない() {
        assertFalse(Onboarding.shouldShow(onboarded = false, hasAccess = true)) // ファイルへのアクセスを許可済み
        assertFalse(Onboarding.shouldShow(onboarded = true, hasAccess = false)) // 案内を見終わった
        assertFalse(Onboarding.shouldShow(onboarded = true, hasAccess = true))
    }

    @Test
    fun 入れ直した直後は_許可が残っていても出す() {
        assertTrue(Onboarding.shouldShow(onboarded = false, hasAccess = true, freshInstall = true))
        assertFalse(Onboarding.shouldShow(onboarded = true, hasAccess = true, freshInstall = true)) // 見終わった後は出さない
    }

    @Test
    fun 入れたばかりかは_時刻が同じかどうかで決める() {
        assertTrue(Onboarding.isFreshInstall(1000L, 1000L))
        assertFalse(Onboarding.isFreshInstall(1000L, 2000L)) // 上書き更新を受けた
    }

    @Test
    fun ファイルへのアクセスの段階は_許可されるまで進めない() {
        assertFalse(Onboarding.canNext(Onboarding.STEP_ACCESS, hasAccess = false))
        assertTrue(Onboarding.canNext(Onboarding.STEP_ACCESS, hasAccess = true))
        assertTrue(Onboarding.canNext(0, hasAccess = false))
        assertTrue(Onboarding.canNext(1, hasAccess = false))
    }

    @Test
    fun 段階は範囲の中に収める() {
        assertEquals(0, Onboarding.clamp(-1))
        assertEquals(Onboarding.STEPS - 1, Onboarding.clamp(Onboarding.STEPS))
        assertEquals(2, Onboarding.clamp(2))
    }

    @Test
    fun アクセスの段階は最後より前にある() {
        assertTrue(Onboarding.STEP_ACCESS < Onboarding.STEPS - 1)
    }
}