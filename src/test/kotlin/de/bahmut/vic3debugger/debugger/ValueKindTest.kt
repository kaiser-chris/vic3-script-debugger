package de.bahmut.vic3debugger.debugger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ValueKindTest {

    // Values as the game rendered them in live sessions.
    @ParameterizedTest(name = "{0} = {1} -> {3}")
    @CsvSource(
        delimiter = '|',
        value = [
            "this|Country Min Raetia (739)|true|SCOPE",
            "scope:state|State Yorkshire (xB5BF4E)|true|SCOPE",
            "gate_magic_researcher|Character Acton Allen (7580)|true|SCOPE",
            "target|TreatyArticle(4294967295)|false|SCOPE",
            "com_opinion|Flag character_opinion_magic_researcher (6327)|false|FLAG",
            "free_elections_var|yes|false|BOOLEAN",
            "gg_tutorial_base_panel_open|no|false|BOOLEAN",
            "result|no — No: has_modifier; Subject: Country Min Raetia (739);|false|BOOLEAN",
            "com_topbar_subsistence_pops|75000.00|false|NUMBER",
            "result|-12.5|false|NUMBER",
            "list:com_topbar_first_line|7 entries|false|LIST",
            "prev|(none)|false|NONE",
            "top scope|Root: Country Min Raetia (739)|false|TEXT",
            "result|not a valid trigger or script value|false|TEXT",
        ],
    )
    fun `infers the kind from the rendered value`(name: String, value: String, expandable: Boolean, expected: ValueKind) {
        assertEquals(expected, ValueKind.of(name, value, expandable))
    }
}
