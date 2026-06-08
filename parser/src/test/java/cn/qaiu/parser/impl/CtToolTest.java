package cn.qaiu.parser.impl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CtToolTest {

    @Test
    public void testExtractPathFromFileUrlVariants() {
        assertEquals("f", CtTool.extractPath("https://url20.ctfile.com/f/12493720-32151037-827af1"));
        assertEquals("f", CtTool.extractPath("https://url20.ctfile.com/f/12493720-32151037-827af1/"));
        assertEquals("f", CtTool.extractPath("https://url20.ctfile.com/f/12493720-32151037-827af1?p=7609"));
        assertEquals("file", CtTool.extractPath("http://url20.ctfile.com/file/12493720-32151037-827af1/"));
        assertEquals("file", CtTool.extractPath("https://url20.ctfile.com/file/12493720-32151037-827af1?p=7609"));
    }

    @Test
    public void testExtractPathFromDirectoryUrlVariants() {
        assertEquals("d", CtTool.extractPath("https://url20.ctfile.com/d/12493720-32151037-827af1"));
        assertEquals("d", CtTool.extractPath("http://url20.ctfile.com/d/12493720-32151037-827af1"));
        assertEquals("d", CtTool.extractPath("https://url20.ctfile.com/d/12493720-32151037-827af1?p=7609&d=32151037&fk=827af1"));
        assertEquals("d", CtTool.extractPath("https://url20.ctfile.com/d/12493720-32151037-827af1/"));
    }
}
