package com.ownerlink.agent

import com.ownerlink.agent.affiliate.AffiliateAccountRules
import com.ownerlink.agent.affiliate.RegistrationDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffiliateAccountTest {
    private fun valid() = RegistrationDraft("Rahim Worker", "01712345678", "24", "MALE", "Graduate", "Dhaka", "example.com", "bKash", "01812345678", "Secure123", "Secure123", "photo")

    @Test fun bangladeshPhoneNormalizationIsStrict() {
        assertEquals("+8801712345678", AffiliateAccountRules.normalizePhone("01712-345678"))
        assertEquals("+8801712345678", AffiliateAccountRules.normalizePhone("+8801712345678"))
        assertNull(AffiliateAccountRules.normalizePhone("123"))
    }

    @Test fun registrationValidationCoversPasswordPayoutAndPhoto() {
        assertTrue(AffiliateAccountRules.validate(valid()).isEmpty())
        assertNotNull(AffiliateAccountRules.validate(valid().copy(confirmPassword = "Different1"))["confirmPassword"])
        assertNotNull(AffiliateAccountRules.validate(valid().copy(password = "weak", confirmPassword = "weak"))["password"])
        assertNotNull(AffiliateAccountRules.validate(valid().copy(withdrawalMethod = "Rocket"))["withdrawalMethod"])
        assertNotNull(AffiliateAccountRules.validate(valid().copy(withdrawalNumber = "12"))["withdrawalNumber"])
        assertNotNull(AffiliateAccountRules.validate(valid().copy(profilePhotoBase64 = ""))["photo"])
    }

    @Test fun noCredentialFieldsArePartOfProfileModel() {
        val names = com.ownerlink.agent.affiliate.AffiliateProfile::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(names.any { it.contains("password") || it.contains("token") || it.contains("pin") })
    }
}
