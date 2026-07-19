package dev.quotaarc.android.data.endpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointDraftValidatorTest {
    @Test
    fun `valid HTTPS endpoint is normalized without persisting anything`() {
        val result = EndpointDraftValidator.validate(" HTTPS://Collector.Example:443/ ")
        assertEquals(
            "https://collector.example",
            (result as EndpointValidationResult.Valid).endpoint.baseUrl,
        )
    }

    @Test
    fun `insecure and credential-bearing drafts fail closed`() {
        val cases = mapOf(
            "http://collector.example" to EndpointDraftError.HTTPS_REQUIRED,
            "https://token@collector.example" to EndpointDraftError.USER_INFO_FORBIDDEN,
            "https://collector.example/v1" to EndpointDraftError.PATH_FORBIDDEN,
            "https://collector.example?token=secret" to EndpointDraftError.QUERY_FORBIDDEN,
            "https://collector.example#fragment" to EndpointDraftError.FRAGMENT_FORBIDDEN,
            "https://" to EndpointDraftError.MALFORMED,
        )
        cases.forEach { (value, expected) ->
            val result = EndpointDraftValidator.validate(value)
            assertTrue(value, result is EndpointValidationResult.Invalid)
            assertEquals(value, expected, (result as EndpointValidationResult.Invalid).reason)
        }
    }
}
