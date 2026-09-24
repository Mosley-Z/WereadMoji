package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.a11y.CardA11yService;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 「怎么用」页（v0.4.2）：把面向**使用者**的说明内置进 App，省得去翻包根那个 md 文件。
 *
 * 文案放 `res/raw/help.txt` —— 改文案不用碰代码，也不至于把几千字塞进 strings.xml。
 * 墨水屏上不需要花哨排版：一个 ScrollView + 一个纯文本 TextView 就够。
 *
 * 注意：这份文案是给用户看的"怎么用"（首次配置 / 卡片怎么点 / App 怎么用），
 * 与 `HANDOFF.md`（给开发者的技术交接）是两回事，别混。
 *
 * ⚠️ **每个自家 Activity 都必须在 onResume/onPause 里通知服务**（v0.4.5 补上的教训）。
 * 桌面卡片的可见性是"事件驱动"的：无障碍服务靠窗口事件推断前台是谁，而**同进程内
 * 从一个 Activity 跳到另一个 Activity 时，不一定有可供判定的窗口事件**。所以必须由
 * "真正知道状态的一方"主动说一句 —— 这是 {@link CardA11yService#noteOwnUiForeground}
 * 存在的原因。本页 v0.4.2 上线时漏了这两句，结果**「设置 → 怎么用」的说明页被桌面卡片
 * 压在下面**（两层字叠在一起，2026-09-23 装机复现）。新增 Activity 时别忘。
 */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.help_layout);
        setTitle(R.string.help_title);

        TextView tv = (TextView) findViewById(R.id.tv_help);
        tv.setText(load());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 和主页 / 设置页一样：告诉服务"用户在自家界面"，桌面卡片要让位
        CardA11yService.noteOwnUiForeground(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CardA11yService.noteOwnUiForeground(false);
    }

    /** 读 res/raw/help.txt；读不到就退成一句提示，绝不因为文案问题崩掉 */
    private String load() {
        InputStream is = null;
        try {
            is = getResources().openRawResource(R.raw.help);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "使用说明读取失败：" + t;
        } finally {
            try {
                if (is != null) is.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
