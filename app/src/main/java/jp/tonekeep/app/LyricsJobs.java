package jp.tonekeep.app;

import android.content.Context;

/** Queues the first download and the requested work together, so no second start is needed. */
final class LyricsJobs {
    static void cancel(Context context,LyricsStore.Task task){
        LyricsStore store=new LyricsStore(context);LyricsStore.Task current=store.find(task.id());if(current==null||!current.active())return;LyricsService.cancel(context,task.id());
        LyricsStore.Task download=store.modelTask(task.model());
        if(download!=null&&download.active()&&store.all().stream().noneMatch(t->!t.kind().equals("model")&&t.model().equals(task.model())&&t.active()))LyricsService.cancel(context,download.id());
    }
    static LyricsStore.Task start(Context context,DownloadStore.Entry entry,String model,boolean japanese,String kind,Lyrics reference) throws Exception {
        LyricsStore store=new LyricsStore(context);ModelManager models=new ModelManager(context);
        boolean cached=kind.equals("align")&&reference!=null&&store.recognition(entry.id(),entry.contentUri(),model,reference.sourceHash())!=null;
        LyricsStore.Task previous=store.forAudio(entry.id(),entry.contentUri());boolean corrupt=previous!=null&&previous.status().equals("failed")&&previous.model().equals(model)&&previous.message().contains("認識データが破損");
        LyricsStore.Prepared prepared=store.prepare(entry.id(),entry.contentUri(),model,japanese,kind,reference,!cached&&(!models.installed(model)||corrupt));
        try{LyricsService.start(context);return prepared.work();}
        catch(RuntimeException error){store.update(prepared.work().id(),"failed","開始できませんでした。歌詞画面から再試行してください。",-1);if(prepared.newDownload())store.update(prepared.download().id(),"failed","処理を開始できませんでした。再試行してください。",-1);throw error;}
    }
    static String model(Context context){String id=context.getSharedPreferences("lyrics-options",Context.MODE_PRIVATE).getString("model","small");return id.equals("medium")?"medium":"small";}
    static boolean japanese(Context context){return context.getSharedPreferences("lyrics-options",Context.MODE_PRIVATE).getBoolean("japanese",true);}
}
