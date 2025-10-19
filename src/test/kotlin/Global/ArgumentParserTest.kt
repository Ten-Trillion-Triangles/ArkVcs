package Global

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ArgumentParserTest {

    @Test
    fun testParseArguments() {
        val parser = ArgumentParser()
        val arguments = listOf("-flag1", "--verbose", "key=value", "host", "=", "localhost", "file.txt")
        
        parser.parse(arguments)
        
        // Test bool flags
        assertEquals(2, parser.boolFlags.size)
        assertTrue(parser.boolFlags.contains("-flag1"))
        assertTrue(parser.boolFlags.contains("--verbose"))
        
        // Test value flags
        assertEquals(2, parser.valueFlags.size)
        assertEquals("value", parser.valueFlags["key"])
        assertEquals("localhost", parser.valueFlags["host"])
        
        // Test remaining args
        assertEquals(1, parser.args.size)
        assertEquals("file.txt", parser.args[0])
    }
    
    @Test
    fun testEmptyArguments() {
        val parser = ArgumentParser()
        parser.parse(emptyList())
        
        assertTrue(parser.boolFlags.isEmpty())
        assertTrue(parser.valueFlags.isEmpty())
        assertTrue(parser.args.isEmpty())
    }
    
    @Test
    fun testOnlyBoolFlags() {
        val parser = ArgumentParser()
        parser.parse(listOf("-a", "-b", "--help"))
        
        assertEquals(3, parser.boolFlags.size)
        assertTrue(parser.valueFlags.isEmpty())
        assertTrue(parser.args.isEmpty())
    }
}