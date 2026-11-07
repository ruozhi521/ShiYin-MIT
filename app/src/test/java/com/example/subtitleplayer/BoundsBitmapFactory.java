package com.example.subtitleplayer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import java.io.InputStream;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBitmapFactory;

/** Robolectric 4.11 legacy shadow 在 bounds 模式仍返回 Bitmap；对齐 Android 的 null 契约。 */
@Implements(BitmapFactory.class)
public class BoundsBitmapFactory extends ShadowBitmapFactory {
    @Implementation
    protected static Bitmap decodeStream(InputStream stream, Rect padding, BitmapFactory.Options options) {
        Bitmap result = ShadowBitmapFactory.decodeStream(stream, padding, options);
        return options != null && options.inJustDecodeBounds ? null : result;
    }
}
