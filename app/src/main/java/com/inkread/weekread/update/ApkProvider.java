package com.inkread.weekread.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把下载好的 APK 暴露给系统安装器。
 *
 * 为什么要手写：Android 7.0 起不能用 file:// URI 传文件给别的应用（会抛
 * FileUriExposedException），必须走 ContentProvider。而常规做法用的
 * androidx.core.content.FileProvider 依赖 AndroidX —— 本工程坚持零依赖、无 AndroidX
 * （目标设备是低内存墨水屏，堆 128–256MB），所以自己实现一个。
 *
 * 安全：只暴露「更新下载」的那一个固定文件名，其余一律拒绝，不做任意路径读取。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.inkread.weekread.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.exists()) {
            throw new FileNotFoundException("不在白名单或文件不存在: " + uri);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 安装器靠它识别这是安装包 */
    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    /** 白名单：只认下载目录下那一个 APK 文件名 */
    private File resolve(Uri uri) {
        if (uri == null || getContext() == null) {
            return null;
        }
        String name = uri.getLastPathSegment();
        if (name == null || !name.equals(ApkInstaller.APK_FILE_NAME)) {
            return null;
        }
        return new File(ApkInstaller.apkDir(getContext()), name);
    }

    // ── 只读暴露，写操作一律不支持 ──

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
