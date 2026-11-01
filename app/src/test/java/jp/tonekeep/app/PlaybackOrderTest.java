package jp.tonekeep.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PlaybackOrderTest {
    @Test public void nextPreviousAndEndRespectTheSelectedSavedFile(){PlaybackOrder q=new PlaybackOrder();q.select(List.of("original","key-plus-two","other"),"original",false);assertFalse(q.canMove(-1,false));assertEquals("key-plus-two",q.move(1,false));assertEquals("other",q.move(1,false));assertNull(q.move(1,false));assertEquals("key-plus-two",q.move(-1,false));}
    @Test public void repeatWrapsWithoutCreatingEntries(){PlaybackOrder q=new PlaybackOrder();q.select(List.of("a","b"),"b",false);assertEquals("a",q.move(1,true));assertEquals("b",q.move(-1,true));q.select(List.of("only"),"only",false);assertEquals("only",q.move(1,true));}
    @Test public void shuffleVisitsEachDistinctIdAndCanReturnToOriginalOrder(){PlaybackOrder q=new PlaybackOrder();q.select(List.of("a","b","c","d"),"c",true);Set<String> visited=new HashSet<>();visited.add("c");String next;while((next=q.move(1,false))!=null)assertTrue(visited.add(next));assertEquals(Set.of("a","b","c","d"),visited);q.select(List.of("a","b","c","d"),"c",true);q.shuffle(false);assertEquals("d",q.move(1,false));assertEquals("c",q.move(-1,false));}
    @Test public void emptyOrMissingSelectionCannotPlaySomethingElse(){PlaybackOrder q=new PlaybackOrder();q.select(List.of(),"missing",true);assertNull(q.move(1,true));q.select(List.of("a"),"missing",false);assertNull(q.move(1,true));}
}
