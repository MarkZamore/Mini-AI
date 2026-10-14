package ai.enterprise.graph;

import ai.enterprise.contracts.Json;
import java.nio.file.*;
import java.util.*;

/** Actual-dataset force/frame benchmark. Inputs are a read-only exported graph snapshot. */
public final class GraphPhysicsDatasetTest {
    public static void main(String[] args)throws Exception{
        var fixture=Json.object(Files.readString(Path.of(args[0])));
        var nodes=(List<?>)fixture.get("nodes");var edges=(List<?>)fixture.get("edges");
        var ids=nodes.stream().map(n->Json.string(Json.map(Json.map(n).get("document")).get("document_id"))).toList();
        var links=edges.stream().map(e->{var row=Json.map(e);return new GraphGeometry.Link(Json.string(row.get("source_id")),Json.string(row.get("target_id")));}).toList();
        var degrees=GraphGeometry.uniqueDegrees(ids,links);
        var physics=new GraphPhysics(ids,links);
        for(int i=0;i<60;i++)physics.step(1.0/60,()->false);
        var samples=new ArrayList<Double>();
        for(int i=0;i<600;i++){
            long before=System.nanoTime();physics.step(1.0/60,()->false);physics.snapshot();
            samples.add((System.nanoTime()-before)/1_000_000.0);
        }
        for(int i=0;i<900&&!physics.sleeping();i++)physics.step(1.0/60,()->false);
        var positions=physics.snapshot();
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=-minX,maxY=-minX,cx=0,cy=0,minRatio=Double.POSITIVE_INFINITY;
        for(var p:positions.values()){minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());cx+=p.x();cy+=p.y();require(Double.isFinite(p.x())&&Double.isFinite(p.y()),"Non-finite actual-dataset frame");}
        for(int a=0;a<ids.size();a++)for(int b=a+1;b<ids.size();b++){
            var p=positions.get(ids.get(a));var q=positions.get(ids.get(b));
            double minimum=GraphGeometry.radius(degrees.get(ids.get(a)))+GraphGeometry.radius(degrees.get(ids.get(b)));
            minRatio=Math.min(minRatio,Math.hypot(p.x()-q.x(),p.y()-q.y())/minimum);
        }
        double aspect=(maxX-minX)/(maxY-minY);
        require(ids.size()==599&&links.size()==647&&degrees.values().stream().filter(d->d==0).count()==335,"Actual dataset identity changed");
        require(aspect>0.70&&aspect<1.43,"Actual graph is not rounded around the origin");
        require(Math.hypot(cx/ids.size(),cy/ids.size())<20,"Actual cloud drifts from the center magnet");
        require(minRatio>0.70,"Actual graph has substantial node overlap");
        samples.sort(Double::compare);
        var report=new LinkedHashMap<String,Object>();
        report.put("documents",ids.size());report.put("confirmedEvidence",links.size());report.put("springs",physics.springCount());report.put("isolates",335);
        report.put("frameMedianMs",samples.get(samples.size()/2));report.put("frameP95Ms",samples.get((int)Math.ceil(samples.size()*0.95)-1));
        report.put("boundsAspect",aspect);report.put("centroidDistance",Math.hypot(cx/ids.size(),cy/ids.size()));report.put("minimumDiscSeparationRatio",minRatio);report.put("sleeping",physics.sleeping());
        report.put("includes","force integration, collision correction and immutable latest-frame snapshot; rendering measured separately");
        if(args.length>1)Files.writeString(Path.of(args[1]),Json.stringify(report));
        System.out.println(Json.stringify(report));
    }
    private static void require(boolean truth,String message){if(!truth)throw new AssertionError(message);}
}
