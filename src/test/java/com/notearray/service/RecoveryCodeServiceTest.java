package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryCodeServiceTest {

    @Test
    void generatedCodesAreUnambiguousAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String code = RecoveryCodeService.generateCode();
            assertTrue(code.matches("[A-HJ-NP-Z2-9]{12}"), code);
            assertTrue(seen.add(code));
        }
    }

    @Test
    void formattingNormalizationAndHashingAreStable() {
        String code = "ABCDEFGHJKLM";
        assertEquals("ABCD-EFGH-JKLM", RecoveryCodeService.format(code));
        assertEquals(code, RecoveryCodeService.normalize("abcd-efgh-jklm"));
        assertEquals(code, RecoveryCodeService.normalize(" ABCD EFGH JKLM "));

        assertNull(RecoveryCodeService.normalize(null));
        assertNull(RecoveryCodeService.normalize("short"));
        assertNull(RecoveryCodeService.normalize("ABCD-EFGH-JKL0")); // 0 is excluded

        String hash = RecoveryCodeService.hash(code);
        assertEquals(64, hash.length());
        assertEquals(hash, RecoveryCodeService.hash(code));
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void challengeTokensAreHashedDeterministically() {
        assertEquals(64, LoginChallengeService.hash("token").length());
        assertEquals(LoginChallengeService.hash("token"), LoginChallengeService.hash("token"));
        assertNotNull(LoginChallengeService.hash("other"));
    }
}
