package cn.qaiu.parser.impl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LzToolTest {

    @Test
    public void testExtractAcwArg1() {
        assertEquals("abc123", LzTool.extractAcwArg1("<script>arg1='abc123';</script>"));
    }

    @Test
    public void testExtractAcwArg1ReturnsNullWhenMissing() {
        assertNull(LzTool.extractAcwArg1("<html>not acw page</html>"));
        assertNull(LzTool.extractAcwArg1("arg1='unterminated"));
        assertNull(LzTool.extractAcwArg1(null));
    }

    @Test
    public void testIsShareCancelledPage() {
        String html = "<div class=\"off\"><div class=\"off0\"><div class=\"off1\"></div></div>来晚啦...文件取消分享了</div>";

        assertTrue(LzTool.isShareCancelledPage(html));
        assertTrue(LzTool.isShareCancelledPage("<html>来晚啦...文件取消分享了</html>"));
        assertTrue(LzTool.isShareCancelledPage("<div class=\"off\">文件已取消分享</div>"));
        assertFalse(LzTool.isShareCancelledPage("<html>文件取消分享说明文案</html>"));
        assertFalse(LzTool.isShareCancelledPage("<html>正常分享</html>"));
        assertFalse(LzTool.isShareCancelledPage("<div class=\"off\">正常提示</div>"));
        assertFalse(LzTool.isShareCancelledPage(null));
    }
}
