package com.howard.mcai.webpanel;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面板页面的静态回归检查。
 *
 * <p>v0.1.5 实测事故：renderSettings 在 for 循环里给密码框写了个 return，
 * 渲染到第 2 项（接口密钥）就整函数退出，后面 39 项设置全没显示，
 * 服主反馈"功能有点少、没看到读取超时"。这里把那次的形状钉死：
 * renderSettings 函数体里不许再出现裸 return。</p>
 */
class PanelHtmlResourceTest {

    private String html() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/webpanel/panel.html")) {
            assertNotNull(in, "jar 里应有 webpanel/panel.html");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void placeholdersAndFeaturesPresent() throws Exception {
        String html = html();
        assertTrue(html.contains("{{API_BASE}}"));
        assertTrue(html.contains("{{TOKEN}}"));
        // 第一期功能标记
        assertTrue(html.contains("exportSettingsBtn"), "应有导出全部设置");
        assertTrue(html.contains("importSettingsBtn"), "应有导入设置");
        assertTrue(html.contains("data-export-data"), "数据页应有导出");
        assertTrue(html.contains("data-import-data"), "数据页应有导入");
        assertTrue(html.contains("resetAllBtn"), "应有全部恢复默认");
    }

    @Test
    void renderSettingsHasNoEarlyReturn() throws Exception {
        String html = html();
        int start = html.indexOf("function renderSettings()");
        int end = html.indexOf("function markDirty");
        assertTrue(start >= 0 && end > start);
        String body = html.substring(start, end);
        assertFalse(body.contains("\n        return;"),
                "renderSettings 里不许再出现提前 return（v0.1.5 截断事故）");
        // 41 项设置靠循环渲染：函数里必须有遍历 fields 的循环
        assertTrue(body.contains("for (const field of state.fields)"));
    }
}
