package jp.tonekeep.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Playback refers to saved file IDs, including distinct key-changed versions. */
final class PlaybackOrder {
    private List<String> original=List.of();
    private final List<String> order=new ArrayList<>();
    private int index=-1;
    private boolean shuffled;
    void select(List<String> ids,String current,boolean shuffle){
        original=List.copyOf(ids);order.clear();order.addAll(ids);index=order.indexOf(current);shuffled=false;
        shuffle(shuffle);
    }
    void shuffle(boolean enabled){
        if(enabled==shuffled||index<0)return;
        String current=order.get(index);order.clear();order.addAll(original);
        if(enabled){order.remove(current);Collections.shuffle(order);order.add(0,current);}
        index=order.indexOf(current);shuffled=enabled;
    }
    boolean canMove(int direction,boolean wrap){return index>=0&&!order.isEmpty()&&(wrap||index+direction>=0&&index+direction<order.size());}
    String move(int direction,boolean wrap){
        if(!canMove(direction,wrap))return null;
        index=Math.floorMod(index+direction,order.size());return order.get(index);
    }
}
