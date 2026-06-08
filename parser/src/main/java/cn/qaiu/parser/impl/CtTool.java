package cn.qaiu.parser.impl;

import cn.qaiu.entity.FileInfo;
import cn.qaiu.entity.ShareLinkInfo;
import cn.qaiu.parser.PanBase;
import cn.qaiu.util.CommonUtils;
import cn.qaiu.util.FileSizeConverter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.uritemplate.UriTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <a href="https://www.ctfile.com">诚通网盘</a>
 */
public class CtTool extends PanBase {
    private static final String API_URL_PREFIX = "https://webapi.ctfile.com";
    private static final String SHARE_FILE_URL_PREFIX = "https://ctfile.com/file/";
    private static final String AJAX_ACCEPT = "application/json, text/javascript, */*; q=0.01";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    // https://webapi.ctfile.com/getfile.php?path=f&f=64115194-17569800420720-06c697&
    // passcode=7609&r=0.6611183001986635&ref=&url=https%3A%2F%2Furl94.ctfile.com%2Ff%2F64115194-17569800420720-06c697%3Fp%3D7609
    private static final String API1 = API_URL_PREFIX + "/getfile.php?path={path}" +
            "&f={shareKey}&passcode={pwd}&r={rand}&ref=&url={url}";

    // https://webapi.ctfile.com/get_file_url.php?uid=64115194&fid=17569800420720&folder_id=0&
    // share_id=&file_chk=af5c8757a49cbc69a557eb3da59b246c&start_time=1780471868&wait_seconds=0&
    // mb=0&app=0&acheck=1&verifycode=1780471868.2951fe63abedf36ec02f34ed5711ce70&rd=0.36350981353622636
    private static final String API2 = API_URL_PREFIX + "/get_file_url.php?" +
            "uid={uid}&fid={fid}&folder_id=0&share_id=&file_chk={file_chk}" +
            "&start_time={start_time}&wait_seconds={wait_seconds}&mb=0&app=0&acheck=1" +
            "&verifycode={verifycode}&rd={rand}";

    // https://webapi.ctfile.com/getdir.php?path=d&d=64115194-164803691-48508c&
    // folder_id=164803691&fk=decb36&passcode=7609&r=0.23...&ref=&url=https://url94.ctfile.com/d/...
    private static final String API_GETDIR = API_URL_PREFIX + "/getdir.php?path={path}" +
            "&d={shareKey}&folder_id={folder_id}&fk={fk}&passcode={pwd}&r={rand}&ref=&url={url}";

    // DataTables参数，用于获取目录文件列表
    private static final String FILE_LIST_PARAMS = "&sEcho=1&iColumns=4&sColumns=%2C%2C%2C" +
            "&iDisplayStart=0&iDisplayLength=500" +
            "&mDataProp_0=0&sSearch_0=&bRegex_0=false&bSearchable_0=true&bSortable_0=false" +
            "&mDataProp_1=1&sSearch_1=&bRegex_1=false&bSearchable_1=true&bSortable_1=true" +
            "&mDataProp_2=2&sSearch_2=&bRegex_2=false&bSearchable_2=true&bSortable_2=true" +
            "&mDataProp_3=3&sSearch_3=&bRegex_3=false&bSearchable_3=true&bSortable_3=true" +
            "&sSearch=&bRegex=false" +
            "&iSortCol_0=3&sSortDir_0=desc&iSortingCols=1";

    // 文件列表HTML解析正则
    private static final Pattern FILE_ID_PATTERN = Pattern.compile("value=[\"']f(\\d+)[\"']");
    private static final Pattern FOLDER_ID_PATTERN = Pattern.compile("value=[\"']d(\\d+)[\"']");
    private static final Pattern FILE_HREF_PATTERN = Pattern.compile("href=[\"']#/f/([^\"']+)[\"']");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("<a\\b[^>]*>([^<]+)</a>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_ICON_PATTERN = Pattern.compile("alt=[\"']([^\"']+)[\"']");
    private static final Pattern SUBDIR_PATTERN = Pattern.compile("load_subdir\\s*\\((\\d+)\\s*,\\s*['\"]([^'\"]+)['\"]\\)");

    /**
     * 子类重写此构造方法不需要添加额外逻辑
     * 如:
     * <blockquote><pre>
     *  public XxTool(String key, String pwd) {
     *      super(key, pwd);
     *  }
     * </pre></blockquote>
     *
     * @param shareLinkInfo 分享链接信息
     */
    public CtTool(ShareLinkInfo shareLinkInfo) {
        super(shareLinkInfo);
    }


    @Override
    public Future<String> parse() {
        final String shareKey = shareLinkInfo.getShareKey();
        if (shareKey == null || shareKey.indexOf('-') == -1) {
            fail("shareKey格式不正确找不到'-': {}", shareKey);
            return promise.future();
        }
        String[] split = shareKey.split("-");
        if (split.length < 2 || split[0].isBlank() || split[1].isBlank()) {
            fail("shareKey格式不正确: {}", shareKey);
            return promise.future();
        }
        String uid = split[0], fid = split[1];
        String path = extractPath(shareLinkInfo.getShareUrl());

        HttpRequest<Buffer> bufferHttpRequest1 = clientSession.getAbs(UriTemplate.of(API1))
                .setTemplateParam("path", path)
                .setTemplateParam("shareKey", shareKey)
                .setTemplateParam("pwd", shareLinkInfo.getSharePassword())
                .setTemplateParam("rand", String.valueOf(Math.random()))
                .setTemplateParam("url", shareLinkInfo.getShareUrl());

        bufferHttpRequest1
                .send().onSuccess(res -> {
                    var resJson = asJson(res);
                    Object fileValue = resJson.getValue("file");
                    if (!(fileValue instanceof JsonObject)) {
                        fail("解析失败, 文件信息为空或格式错误, 可能分享已失效: {}", resJson);
                        return;
                    }
                    var fileJson = (JsonObject) fileValue;
                    String fileChk = fileJson.getString("file_chk");
                    String startTime = valueToString(fileJson.getValue("start_time"));
                    String waitSeconds = valueToString(fileJson.getValue("wait_seconds"));
                    String verifycode = fileJson.getString("verifycode");
                    if (fileChk == null || fileChk.isBlank()
                            || startTime.isBlank() || waitSeconds.isBlank()
                            || verifycode == null || verifycode.isBlank()) {
                        fail("解析失败, 下载参数不完整, 可能分享已失效或者分享密码不对: {}", fileJson);
                        return;
                    }

                    // 提取文件信息并存储
                    FileInfo fileInfo = new FileInfo()
                            .setFileName(fileJson.getString("file_name"))
                            .setFileId(String.valueOf(fileJson.getLong("file_id", 0L)))
                            .setSizeStr(fileJson.getString("file_size"))
                            .setCreateTime(fileJson.getString("file_time"))
                            .setCreateBy(fileJson.getString("username"))
                            .setFileType("file")
                            .setPanType(shareLinkInfo.getType());
                    shareLinkInfo.getOtherParam().put("fileInfo", fileInfo);

                    HttpRequest<Buffer> bufferHttpRequest2 = clientSession.getAbs(UriTemplate.of(API2))
                            .setTemplateParam("uid", uid)
                            .setTemplateParam("fid", fid)
                            .setTemplateParam("file_chk", fileChk)
                            .setTemplateParam("start_time", startTime)
                            .setTemplateParam("wait_seconds", waitSeconds)
                            .setTemplateParam("verifycode", verifycode)
                            .setTemplateParam("rand", String.valueOf(Math.random()));
                    bufferHttpRequest2
                            .send().onSuccess(res2 -> {
                                JsonObject resJson2 = asJson(res2);
                                String downloadUrl = resJson2.getString("downurl");
                                if (downloadUrl == null || downloadUrl.isBlank()) {
                                    fail("解析失败, 可能分享已失效: json: {} 字段 {} 不存在", resJson2, "downurl");
                                    return;
                                }

                                // 存储下载元数据，包括必要的请求头
                                Map<String, String> headers = new HashMap<>();
                                headers.put("User-Agent", BROWSER_UA);
                                if (shareLinkInfo.getShareUrl() != null && !shareLinkInfo.getShareUrl().isBlank()) {
                                    headers.put("Referer", shareLinkInfo.getShareUrl());
                                }

                                // 使用新的 completeWithMeta 方法
                                completeWithMeta(downloadUrl, headers);
                            }).onFailure(handleFail(bufferHttpRequest1.queryParams().toString()));
                }).onFailure(handleFail(bufferHttpRequest1.queryParams().toString()));
        return promise.future();
    }

    @Override
    public Future<List<FileInfo>> parseFileList() {
        Promise<List<FileInfo>> listPromise = Promise.promise();

        final String shareKey = shareLinkInfo.getShareKey();
        final String shareUrl = shareLinkInfo.getShareUrl();
        final String pwd = shareLinkInfo.getSharePassword();

        // shareKey格式: uid-folder_id-hash (例如 64115194-164803691-48508c)
        if (shareKey == null) {
            listPromise.fail(baseMsg() + " shareKey为空");
            return listPromise.future();
        }
        String[] split = shareKey.split("-");
        if (split.length < 2) {
            listPromise.fail(baseMsg() + " shareKey格式不正确: " + shareKey);
            return listPromise.future();
        }
        String path = extractPath(shareUrl);
        Object dirId = shareLinkInfo.getOtherParam() == null ? null : shareLinkInfo.getOtherParam().get("dirId");
        DirectoryContext directoryContext = resolveDirectoryContext(shareUrl, dirId);

        HttpRequest<Buffer> getDirRequest = withCtAjaxHeaders(clientSession.getAbs(UriTemplate.of(API_GETDIR))
                .setTemplateParam("path", path)
                .setTemplateParam("shareKey", shareKey)
                .setTemplateParam("folder_id", directoryContext.folderId)
                .setTemplateParam("fk", directoryContext.folderKey)
                .setTemplateParam("pwd", pwd != null ? pwd : "")
                .setTemplateParam("rand", String.valueOf(Math.random()))
                .setTemplateParam("url", shareUrl), shareUrl);

        getDirRequest.send().onSuccess(res -> {
            var resJson = asJson(res);
            if (resJson == null || resJson.isEmpty()) {
                listPromise.fail(baseMsg() + " 目录解析失败: 上游返回空响应或非JSON响应");
                return;
            }
            if (!resJson.containsKey("file")) {
                listPromise.fail(baseMsg() + " 目录解析失败: " + resJson.encode());
                return;
            }
            Object dirInfoValue = resJson.getValue("file");
            if (!(dirInfoValue instanceof JsonObject)) {
                listPromise.fail(baseMsg() + " 目录解析失败: file字段格式错误: " + resJson.encode());
                return;
            }
            JsonObject dirInfo = (JsonObject) dirInfoValue;
            Object fileListUrlValue = dirInfo.getValue("url");
            String fileListRelUrl = fileListUrlValue instanceof String ? ((String) fileListUrlValue).trim() : "";
            if (fileListRelUrl.isBlank()) {
                listPromise.fail(baseMsg() + " " + buildDirectoryFailureMessage(resJson, dirInfo));
                return;
            }

            String fileListUrl = appendQueryParams(toCtApiUrl(fileListRelUrl),
                    FILE_LIST_PARAMS + "&_=" + System.currentTimeMillis());
            withCtAjaxHeaders(clientSession.getAbs(fileListUrl), shareUrl)
                    .send().onSuccess(res2 -> {
                        var listJson = asJson(res2);
                        if (listJson == null || listJson.isEmpty()) {
                            listPromise.fail(baseMsg() + " 文件列表解析失败: 上游返回空响应或非JSON响应");
                            return;
                        }
                        Object aaDataValue = listJson.getValue("aaData");
                        if (!(aaDataValue instanceof JsonArray)) {
                            listPromise.fail(baseMsg() + " 文件列表解析失败: aaData为空: " + listJson.encode());
                            return;
                        }
                        JsonArray aaData = (JsonArray) aaDataValue;
                        List<FileInfo> fileList = new ArrayList<>();
                        String panType = shareLinkInfo.getType();
                        for (int i = 0; i < aaData.size(); i++) {
                            try {
                                Object rowValue = aaData.getValue(i);
                                if (!(rowValue instanceof JsonArray)) {
                                    log.warn("城通文件列表行格式错误: {}", rowValue);
                                    continue;
                                }
                                FileInfo fileInfo = parseFileListRow((JsonArray) rowValue, panType,
                                        getDomainName(), shareUrl, pwd);
                                if (fileInfo != null) {
                                    fileList.add(fileInfo);
                                }
                            } catch (Exception e) {
                                log.warn("解析文件行失败: {}", e.getMessage());
                            }
                        }
                        listPromise.complete(fileList);
                    }).onFailure(listPromise::fail);
        }).onFailure(listPromise::fail);

        return listPromise.future();
    }

    @Override
    public Future<String> parseById() {
        Object paramValue = shareLinkInfo.getOtherParam().get("paramJson");
        if (!(paramValue instanceof JsonObject)) {
            Promise<String> parsePromise = Promise.promise();
            parsePromise.fail(baseMsg() + " 缺少下载参数paramJson");
            return parsePromise.future();
        }
        JsonObject paramJson = (JsonObject) paramValue;
        if (!applyFileParam(shareLinkInfo, paramJson)) {
            Promise<String> parsePromise = Promise.promise();
            parsePromise.fail(baseMsg() + " 下载参数id为空");
            return parsePromise.future();
        }
        return parse();
    }

    static boolean applyFileParam(ShareLinkInfo shareLinkInfo, JsonObject paramJson) {
        String fileShareKey = paramJson.getString("id");
        if (fileShareKey == null || fileShareKey.isBlank()) {
            return false;
        }
        shareLinkInfo.setSharePassword(paramJson.getString("pwd", ""));
        shareLinkInfo.setShareKey(fileShareKey);
        shareLinkInfo.setShareUrl(SHARE_FILE_URL_PREFIX + fileShareKey);
        shareLinkInfo.setStandardUrl(SHARE_FILE_URL_PREFIX + fileShareKey);
        return true;
    }

    private HttpRequest<Buffer> withCtAjaxHeaders(HttpRequest<Buffer> request, String shareUrl) {
        request.putHeader("User-Agent", BROWSER_UA)
                .putHeader("Accept", AJAX_ACCEPT)
                .putHeader("X-Requested-With", "XMLHttpRequest");
        if (shareUrl != null && !shareUrl.isBlank()) {
            request.putHeader("Referer", shareUrl);
        }
        String origin = extractOrigin(shareUrl);
        if (!origin.isBlank()) {
            request.putHeader("Origin", origin);
        }
        return request;
    }

    static FileInfo parseFileListRow(JsonArray row, String panType, String domainName, String shareUrl, String pwd) {
        if (row == null || row.size() < 2) {
            return null;
        }
        String checkboxHtml = rowString(row, 0);
        String nameCellHtml = rowString(row, 1);
        String sizeStr = rowString(row, 2).trim();
        String dateStr = rowString(row, 3).trim();
        if (nameCellHtml.isBlank()) {
            return null;
        }

        String fileName = matchFirst(FILE_NAME_PATTERN, nameCellHtml);
        String fileIcon = matchFirst(FILE_ICON_PATTERN, nameCellHtml);
        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        Matcher subdirMatcher = SUBDIR_PATTERN.matcher(nameCellHtml);
        boolean hasSubdirCall = subdirMatcher.find();
        if (hasSubdirCall || "folder".equalsIgnoreCase(fileIcon)) {
            String folderId = hasSubdirCall ? subdirMatcher.group(1) : null;
            String folderKey = hasSubdirCall ? subdirMatcher.group(2) : "";
            if (folderId == null) {
                folderId = matchFirst(FOLDER_ID_PATTERN, checkboxHtml);
            }
            if (folderId == null || folderId.isBlank()) {
                return null;
            }
            String dirId = folderId + ":" + folderKey;
            FileInfo fileInfo = new FileInfo()
                    .setFileName(fileName.trim())
                    .setFileId(folderId)
                    .setSize(0L)
                    .setSizeStr(sizeStr.isBlank() ? "0B" : sizeStr)
                    .setFileType("folder")
                    .setFileIcon(fileIcon)
                    .setPanType(panType)
                    .setParserUrl(buildFolderParserUrl(domainName, shareUrl, dirId, pwd));
            if (!dateStr.isBlank()) {
                fileInfo.setCreateTime(dateStr).setUpdateTime(dateStr);
            }
            return fileInfo;
        }

        String fileShareKey = matchFirst(FILE_HREF_PATTERN, nameCellHtml);
        if (fileShareKey == null || fileShareKey.isBlank()) {
            return null;
        }
        String fileId = matchFirst(FILE_ID_PATTERN, checkboxHtml);
        JsonObject paramJson = new JsonObject()
                .put("id", fileShareKey)
                .put("fileName", fileName.trim())
                .put("pwd", pwd == null ? "" : pwd);
        String param = CommonUtils.urlBase64Encode(paramJson.encode());
        long sizeBytes = 0;
        try {
            sizeBytes = sizeStr.isBlank() ? 0 : FileSizeConverter.convertToBytes(sizeStr);
        } catch (Exception ignored) {
        }

        FileInfo fileInfo = new FileInfo()
                .setFileName(fileName.trim())
                .setFileId(fileId)
                .setSizeStr(sizeStr)
                .setSize(sizeBytes)
                .setFileType(fileIcon != null ? fileIcon : "file")
                .setFileIcon(fileIcon)
                .setPanType(panType)
                .setParserUrl(String.format("%s/v2/redirectUrl/%s/%s",
                        domainName, panType, param));
        if (!dateStr.isBlank()) {
            fileInfo.setCreateTime(dateStr).setUpdateTime(dateStr);
        }
        return fileInfo;
    }

    private static String buildFolderParserUrl(String domainName, String shareUrl, String dirId, String pwd) {
        String url = String.format("%s/v2/getFileList?url=%s&dirId=%s",
                domainName, urlEncode(shareUrl), urlEncode(dirId));
        if (pwd != null && !pwd.isBlank()) {
            url += "&pwd=" + urlEncode(pwd);
        }
        return url;
    }

    static String extractQueryParam(String url, String paramName) {
        if (url == null || paramName == null) return null;
        int qIdx = url.indexOf('?');
        if (qIdx < 0) return null;
        String query = url.substring(qIdx + 1);
        int fragmentIdx = query.indexOf('#');
        if (fragmentIdx >= 0) {
            query = query.substring(0, fragmentIdx);
        }
        for (String param : query.split("&")) {
            int eqIdx = param.indexOf('=');
            if (eqIdx > 0 && urlDecode(param.substring(0, eqIdx)).equals(paramName)) {
                return urlDecode(param.substring(eqIdx + 1));
            }
        }
        return null;
    }

    static String extractPath(String shareUrl) {
        if (shareUrl == null) {
            return "";
        }
        int comIdx = shareUrl.indexOf("com/");
        if (comIdx < 0) {
            return "";
        }

        int pathStart = comIdx + 4;
        int pathEnd = shareUrl.indexOf('/', pathStart);
        if (pathEnd < 0) {
            pathEnd = shareUrl.indexOf('?', pathStart);
        }
        if (pathEnd < 0) {
            pathEnd = shareUrl.length();
        }
        return shareUrl.substring(pathStart, pathEnd);
    }

    static String extractFolderKey(String shareUrl) {
        return trimToEmpty(extractQueryParam(shareUrl, "fk"));
    }

    static DirectoryContext resolveDirectoryContext(String shareUrl, Object dirIdObj) {
        String dirId = dirIdObj == null ? "" : urlDecode(String.valueOf(dirIdObj).trim());
        if (!dirId.isBlank()) {
            String[] split = dirId.split(":", 2);
            return new DirectoryContext(trimToDefault(split[0], "undefined"),
                    split.length > 1 ? trimToEmpty(split[1]) : "");
        }

        String queryFolderId = firstNonBlank(extractQueryParam(shareUrl, "folder_id"), extractQueryParam(shareUrl, "d"));
        String queryFk = extractFolderKey(shareUrl);
        if (!queryFolderId.isBlank() || !queryFk.isBlank()) {
            return new DirectoryContext(trimToDefault(queryFolderId, "undefined"), queryFk);
        }
        return new DirectoryContext("undefined", "");
    }

    static String buildDirectoryFailureMessage(JsonObject resJson, JsonObject dirInfo) {
        String code = valueToString(resJson.getValue("code"));
        String message = valueToString(dirInfo.getValue("message"));
        if (message != null && !message.isBlank()) {
            return "目录解析失败: " + message + " (code=" + code + ")";
        }
        if ("423".equals(code)) {
            return "目录解析失败: 需要访问密码或该分享受限 (code=423)";
        }
        return "目录解析失败: 文件列表URL为空, 上游响应: " + resJson.encode();
    }

    private static String toCtApiUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return API_URL_PREFIX + url;
    }

    private static String appendQueryParams(String url, String params) {
        String normalizedParams = params != null && params.startsWith("&") ? params.substring(1) : params;
        return url + (url.contains("?") ? "&" : "?") + normalizedParams;
    }

    private static String rowString(JsonArray row, int index) {
        if (row == null || index >= row.size()) {
            return "";
        }
        return valueToString(row.getValue(index));
    }

    private static String valueToString(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String matchFirst(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstNonBlank(String first, String second) {
        return !trimToEmpty(first).isBlank() ? trimToEmpty(first) : trimToEmpty(second);
    }

    private static String trimToDefault(String value, String defaultValue) {
        String result = trimToEmpty(value);
        return result.isBlank() ? defaultValue : result;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private static String extractOrigin(String shareUrl) {
        try {
            URI uri = URI.create(shareUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "";
            }
            String origin = uri.getScheme() + "://" + uri.getHost();
            return uri.getPort() > 0 ? origin + ":" + uri.getPort() : origin;
        } catch (Exception e) {
            return "";
        }
    }

    static final class DirectoryContext {
        final String folderId;
        final String folderKey;

        DirectoryContext(String folderId, String folderKey) {
            this.folderId = folderId;
            this.folderKey = folderKey;
        }
    }
}
