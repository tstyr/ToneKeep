package jp.tonekeep.app;

import android.graphics.*;
import android.widget.ImageView;
import android.util.LruCache;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

final class ArtworkLoader {
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final LruCache<String,Bitmap> cache=new LruCache<>(16*1024*1024){@Override protected int sizeOf(String key,Bitmap value){return value.getByteCount();}};
    static void load(ImageView view,String path){load(view,path,256,b->{});}
    static void load(ImageView view,String path,int size,Consumer<Bitmap> ready){
        String key=path+"@"+size;view.setTag(key);Bitmap hit=cache.get(key);
        if(hit!=null){view.setImageBitmap(hit);ready.accept(hit);return;}
        worker.submit(()->{
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);
            if(o.outWidth<1||o.outHeight<1||o.outWidth>20000||o.outHeight>20000)return;
            o.inSampleSize=1;while(o.outWidth/(o.inSampleSize*2)>=size||o.outHeight/(o.inSampleSize*2)>=size)o.inSampleSize*=2;
            o.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeFile(path,o);
            if(b!=null){cache.put(key,b);view.post(()->{if(key.equals(view.getTag())){view.setImageBitmap(b);ready.accept(b);}});}
        });
    }
}
