package cn.qaiu.parser.impl;

import cn.qaiu.entity.FileInfo;
import cn.qaiu.entity.ShareLinkInfo;
import cn.qaiu.util.CommonUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void testExtractFolderKeyOnlyFromQuery() {
        assertEquals("827af1", CtTool.extractFolderKey(
                "https://url20.ctfile.com/d/12493720-32151037-wrong?d=32151037&fk=827af1"));
        assertEquals("", CtTool.extractFolderKey(
                "https://url20.ctfile.com/d/12493720-32151037-827af1"));
        assertEquals("e8a1a0", CtTool.extractFolderKey(
                "https://url20.ctfile.com/d/12493720-32151037-827af1?fk=e8a1a0#fragment"));
    }

    @Test
    public void testResolveDirectoryContext() {
        CtTool.DirectoryContext root = CtTool.resolveDirectoryContext(
                "https://url20.ctfile.com/d/12493720-32151037-827af1",
                null);
        assertEquals("undefined", root.folderId);
        assertEquals("", root.folderKey);

        CtTool.DirectoryContext query = CtTool.resolveDirectoryContext(
                "https://url20.ctfile.com/d/12493720-32151037-wrong?d=32151037&fk=827af1",
                null);
        assertEquals("32151037", query.folderId);
        assertEquals("827af1", query.folderKey);

        CtTool.DirectoryContext child = CtTool.resolveDirectoryContext(
                "https://url20.ctfile.com/d/12493720-32151037-827af1",
                "32151037:e8a1a0");
        assertEquals("32151037", child.folderId);
        assertEquals("e8a1a0", child.folderKey);
    }

    @Test
    public void testBuildDirectoryFailureMessage() {
        assertEquals("目录解析失败: 访问密码不正确，请重试。 (code=423)",
                CtTool.buildDirectoryFailureMessage(
                        io.vertx.core.json.JsonObject.of("code", 423),
                        io.vertx.core.json.JsonObject.of("message", "访问密码不正确，请重试。")));
        assertEquals("目录解析失败: 需要访问密码或该分享受限 (code=423)",
                CtTool.buildDirectoryFailureMessage(
                        io.vertx.core.json.JsonObject.of("code", 423),
                        io.vertx.core.json.JsonObject.of("folder_id", 32151037)));
    }

    @Test
    public void testBuildFileListParamsUsesAcceptedPageLength() {
        String params = CtTool.buildFileListParams(0, 200);

        assertTrue(params.contains("iDisplayStart=0"));
        assertTrue(params.contains("iDisplayLength=200"));
        assertTrue(params.contains("sColumns=%2C%2C%2C"));
        assertFalse(params.contains("iDisplayLength=500"));
        assertFalse(params.contains("{start}"));
        assertFalse(params.contains("{length}"));
    }

    @Test
    public void testShouldFetchNextFileListPage() {
        assertTrue(CtTool.shouldFetchNextFileListPage(0, 200, 201));
        assertFalse(CtTool.shouldFetchNextFileListPage(200, 1, 201));
        assertFalse(CtTool.shouldFetchNextFileListPage(0, 0, 201));
    }

    @Test
    public void testUnexpectedEmptyPageFailsWhenTotalNotReached() {
        assertTrue(CtTool.isUnexpectedEmptyFileListPage(200, 0, 300));
        assertFalse(CtTool.isUnexpectedEmptyFileListPage(300, 0, 300));
        assertFalse(CtTool.isUnexpectedEmptyFileListPage(0, 0, 0));
    }

    @Test
    public void testParseFileListTotalAcceptsStringOrFallback() {
        assertEquals(201, CtTool.parseFileListTotal(new JsonObject()
                .put("iTotalDisplayRecords", "201")
                .put("iTotalRecords", 300)));
        assertEquals(300, CtTool.parseFileListTotal(new JsonObject()
                .put("iTotalRecords", "300")));
    }

    @Test
    public void testParseFolderRowBuildsEncodedParserUrl() {
        JsonArray row = JsonArray.of(
                "<input type=\"checkbox\" value=\"d32151037\">",
                "<img alt=\"folder\"><a onclick=\"load_subdir(32151037, 'e8a1a0')\">SecureCRT</a>",
                "- -",
                "2019-01-14");

        FileInfo fileInfo = CtTool.parseFileListRow(row, "ctd", "http://localhost:6400",
                "https://url20.ctfile.com/d/12493720-32151037-827af1?p=1&x=2", "osssr");

        assertNotNull(fileInfo);
        assertEquals("SecureCRT", fileInfo.getFileName());
        assertEquals("32151037", fileInfo.getFileId());
        assertEquals("folder", fileInfo.getFileType());
        assertEquals(Long.valueOf(0L), fileInfo.getSize());
        assertEquals("- -", fileInfo.getSizeStr());
        assertEquals("2019-01-14", fileInfo.getCreateTime());
        assertTrue(fileInfo.getParserUrl().contains("/v2/getFileList?url="));
        assertTrue(fileInfo.getParserUrl().contains("url=https%3A%2F%2Furl20.ctfile.com%2Fd%2F12493720-32151037-827af1%3Fp%3D1%26x%3D2"));
        assertTrue(fileInfo.getParserUrl().contains("dirId=32151037%3Ae8a1a0"));
        assertTrue(fileInfo.getParserUrl().contains("pwd=osssr"));
    }

    @Test
    public void testParseFileRowKeepsRedirectUrl() {
        JsonArray row = JsonArray.of(
                "<input type=\"checkbox\" value=\"f17569800420720\">",
                "<img alt=\"zip\"><a href=\"#/f/64115194-17569800420720-06c697\">demo.zip</a>",
                "12.5 M",
                "2026-06-08");

        FileInfo fileInfo = CtTool.parseFileListRow(row, "ctd", "http://localhost:6400",
                "https://url20.ctfile.com/d/12493720-32151037-827af1", "osssr");
        String expectedParam = CommonUtils.urlBase64Encode(new JsonObject()
                .put("id", "64115194-17569800420720-06c697")
                .put("fileName", "demo.zip")
                .put("pwd", "osssr")
                .encode());

        assertNotNull(fileInfo);
        assertEquals("demo.zip", fileInfo.getFileName());
        assertEquals("17569800420720", fileInfo.getFileId());
        assertEquals("zip", fileInfo.getFileType());
        assertEquals("2026-06-08", fileInfo.getUpdateTime());
        assertEquals("http://localhost:6400/v2/redirectUrl/ctd/" + expectedParam,
                fileInfo.getParserUrl());
    }

    @Test
    public void testApplyFileParamRestoresPasswordAndFileUrl() {
        ShareLinkInfo shareLinkInfo = ShareLinkInfo.newBuilder()
                .type("ctd")
                .panName("城通网盘-目录")
                .sharePassword("-")
                .build();
        JsonObject paramJson = new JsonObject()
                .put("id", "64115194-17569800420720-06c697")
                .put("pwd", "osssr");

        assertTrue(CtTool.applyFileParam(shareLinkInfo, paramJson));

        assertEquals("64115194-17569800420720-06c697", shareLinkInfo.getShareKey());
        assertEquals("osssr", shareLinkInfo.getSharePassword());
        assertEquals("https://ctfile.com/file/64115194-17569800420720-06c697", shareLinkInfo.getShareUrl());
        assertEquals("https://ctfile.com/file/64115194-17569800420720-06c697", shareLinkInfo.getStandardUrl());
    }

    @Test
    public void testParseInvalidRowReturnsNull() {
        assertNull(CtTool.parseFileListRow(JsonArray.of("bad"), "ctd", "", "", ""));
    }
}
