package com.mordor.kelly.kelsy.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchTermsTest {
    @Test void preservesRunsAndMixedScriptConstraints() {
        assertEquals(SearchTerms.match("张三 交付"),SearchTerms.match("张三，交付"));
        assertTrue(SearchTerms.match("licence许可证").contains("\"许可\""));
        assertFalse(SearchTerms.match("licence许可证").contains("\"许可证\""));
        assertEquals(2,SearchTerms.groups("许可证licence").size());
        assertEquals(SearchTerms.match("ＡＢＣ 𠀀𠀁𠀂"),SearchTerms.match("abc 𠀀𠀁𠀂"));
        assertTrue(SearchTerms.match("\" OR *").contains("\"or\""));
    }
}
