package de.bahmut.vic3debugger.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LaunchOptionsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "-script_debugger=8080, 8080",
        "--script_debugger=9229, 9229",
        "-script_debugger, 6111",
        "-script_debugger=, 6111",
        "-script_debugger=0, 6111",
        "-script_debugger=65535, 65535",
        "-script_debugger=65536, 6111",
        "-script_debugger=-5, 6111",
        "-script_debugger=abc, 6111",
        "-script_debugger=8080abc, 8080", // atoi stops at the first non-digit
        "-script_debugger= 8080, 8080",
        "-script_debugger=99999999999999999999, 6111",
    )
    fun `reads the port the way the game does`(argument: String, expected: Int) {
        assertEquals(expected, LaunchOptions.scriptDebuggerPort(listOf("-debug_mode", argument)))
    }

    @Test
    fun `no option means no debugger`() {
        assertNull(LaunchOptions.scriptDebuggerPort(listOf("-debug_mode", "--window-title=Manaflow Test")))
        assertNull(LaunchOptions.scriptDebuggerPort(listOf("-script_debugger_extra=8080", "script_debugger=8080")))
    }

    @Test
    fun `splits the command line of a real game`() {
        val commandLine = "\"E:\\SteamLibrary\\steamapps\\common\\Victoria 3\\binaries\\victoria3.exe\"  -debug_mode " +
            "-script_debugger=8080 --window-title=Manaflow Test"
        assertEquals(
            listOf(
                "E:\\SteamLibrary\\steamapps\\common\\Victoria 3\\binaries\\victoria3.exe",
                "-debug_mode", "-script_debugger=8080", "--window-title=Manaflow", "Test",
            ),
            LaunchOptions.splitWindowsCommandLine(commandLine),
        )
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
        delimiter = '|',
        value = [
            """a "b c" d|a;b c;d""",
            """"a\\"b|a\b""",
            """a\\\"b|a\"b""",
            """a\\b|a\\b""",
            """"a ""quoted"" b"|a "quoted" b""",
            """"" x|;x""",
            """  a   b  |a;b""",
        ],
    )
    fun `follows CommandLineToArgvW`(commandLine: String, expected: String) {
        assertEquals(expected.split(';'), LaunchOptions.splitWindowsCommandLine(commandLine))
    }

    @Test
    fun `parses the Windows process listing`() {
        val listing = "389468\tE:\\Victoria 3\\binaries\\victoria3.exe\t\"E:\\Victoria 3\\binaries\\victoria3.exe\" -script_debugger=8080\r\n" +
            "4242\t\t\r\n" +
            "\r\n" +
            "17\tC:\\victoria3.exe\tvictoria3.exe -debug_mode\n"
        val games = GameProcessFinder.parseWindowsListing(listing)
        assertEquals(3, games.size)
        assertEquals(GameProcess(389468, "E:\\Victoria 3\\binaries\\victoria3.exe", listOf("-script_debugger=8080"), 8080), games[0])
        assertEquals(GameProcess(4242, null, null, null), games[1]) // access denied: arguments unknown
        assertEquals(GameProcess(17, "C:\\victoria3.exe", listOf("-debug_mode"), null), games[2])
    }
}
