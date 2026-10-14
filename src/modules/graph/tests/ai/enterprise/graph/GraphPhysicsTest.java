package ai.enterprise.graph;

import java.util.*;

public final class GraphPhysicsTest {
    public static void main(String[] args)throws Exception{
        var solo=new GraphPhysics(List.of("A"),List.of());
        solo.pin("A",500,0);solo.step(1.0/60,()->false);solo.releaseAt("A",500,0);
        for(int i=0;i<120;i++)solo.step(1.0/60,()->false);
        require(solo.snapshot().get("A").x()<450,"Center magnet attracts an isolated document");
        var pair=new GraphPhysics(List.of("A","B"),List.of(new GraphGeometry.Link("A","B"),new GraphGeometry.Link("B","A"),new GraphGeometry.Link("A","A")));
        require(pair.springCount()==1,"Self references and repeated directions do not duplicate springs");
        settle(pair,800);
        double before=pair.snapshot().get("B").x();
        pair.pin("A",500,0);
        for(int i=0;i<120;i++)pair.step(1.0/60,()->false);
        require(pair.snapshot().get("A").equals(new GraphLayout.Point(500,0)),"Dragged node stays exactly pinned");
        require(pair.snapshot().get("B").x()>before+80,"A connected neighbor follows the spring during dragging");
        pair.releaseAt("A",530,10);
        require(pair.snapshot().get("A").equals(new GraphLayout.Point(530,10)),"Final pointer position survives a coalesced release");
        for(int i=0;i<60;i++)pair.step(1.0/60,()->false);
        require(pair.snapshot().get("A").x()<525,"Release moves smoothly back under gravity and springs");
        var ids=new ArrayList<String>();for(int i=0;i<200;i++)ids.add(String.format(Locale.ROOT,"%03d",i));
        var cloud=new GraphPhysics(ids,List.of());settle(cloud,1600);
        var positions=cloud.snapshot();
        require(positions.size()==ids.size(),"Every isolated node remains in the same graph");
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=-minX,maxY=-minX,cx=0,cy=0,minDistance=Double.POSITIVE_INFINITY;
        for(var p:positions.values()){require(Double.isFinite(p.x())&&Double.isFinite(p.y()),"Finite positions");minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());cx+=p.x();cy+=p.y();}
        double ratio=(maxX-minX)/(maxY-minY);require(ratio>0.75&&ratio<1.3,"Orphans form a rounded cloud, not a side grid");
        require(Math.hypot(cx/ids.size(),cy/ids.size())<15,"Cloud is attracted to the stable world origin");
        var ordered=positions.values().stream().toList();for(int a=0;a<ordered.size();a++)for(int b=a+1;b<ordered.size();b++)minDistance=Math.min(minDistance,Math.hypot(ordered.get(a).x()-ordered.get(b).x(),ordered.get(a).y()-ordered.get(b).y()));
        require(minDistance>29,"Collision separation prevents substantial disc overlap");
        require(cloud.sleeping(),"Damping and cooling eventually suspend background physics");
        var cloudAgain=new GraphPhysics(ids,List.of());settle(cloudAgain,1600);require(cloudAgain.snapshot().equals(positions),"Same seed and bounded steps are reproducible");
        try{new GraphPhysics(ids,List.of()).step(1.0/60,()->true);throw new AssertionError("Cancellation ignored");}catch(java.util.concurrent.CancellationException expected){}
        try(var simulation=new GraphSimulation(new GraphPhysics(List.of("A","B"),List.of(new GraphGeometry.Link("A","B"))))){
            for(int i=0;i<1000;i++)simulation.pin("A",100+i,0);
            simulation.release("A",600,0);
            Thread.sleep(100);
            require(simulation.failure()==null,"Coalesced pointer commands do not fail the worker");
            require(simulation.frame().positions().get("A").x()>570,"A final release is not lost behind intermediate commands");
            simulation.close();require(simulation.awaitStopped(1000),"Disposal stops the simulation owner thread");
            long revision=simulation.frame().revision();simulation.pin("A",999,0);Thread.sleep(40);require(simulation.frame().revision()==revision,"Closed simulation publishes no new frames");
        }
        System.out.println("GraphPhysicsTest: PASS");
    }
    private static void settle(GraphPhysics physics,int count){for(int i=0;i<count&&!physics.sleeping();i++)physics.step(1.0/60,()->false);}
    private static void require(boolean truth,String message){if(!truth)throw new AssertionError(message);}
}
