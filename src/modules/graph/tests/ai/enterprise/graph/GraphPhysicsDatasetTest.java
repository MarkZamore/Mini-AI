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
        int activeSteps=0;
        for(int i=0;i<60&&!physics.sleeping();i++){physics.step(1.0/60,()->false);activeSteps++;}
        var samples=new ArrayList<Double>();
        for(int i=activeSteps;i<720&&!physics.sleeping();i++){
            long before=System.nanoTime();physics.step(1.0/60,()->false);physics.snapshot();
            samples.add((System.nanoTime()-before)/1_000_000.0);activeSteps++;
        }
        require(physics.sleeping(),"Actual graph did not become static within 720 fixed steps");
        var positions=physics.snapshot();
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=-minX,maxY=-minX,cx=0,cy=0,minRatio=Double.POSITIVE_INFINITY,minGap=Double.POSITIVE_INFINITY;
        for(var p:positions.values()){minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());cx+=p.x();cy+=p.y();require(Double.isFinite(p.x())&&Double.isFinite(p.y()),"Non-finite actual-dataset frame");}
        for(int a=0;a<ids.size();a++)for(int b=a+1;b<ids.size();b++){
            var p=positions.get(ids.get(a));var q=positions.get(ids.get(b));
            double minimum=GraphGeometry.radius(degrees.get(ids.get(a)))+GraphGeometry.radius(degrees.get(ids.get(b)));
            double distance=Math.hypot(p.x()-q.x(),p.y()-q.y());
            minRatio=Math.min(minRatio,distance/minimum);minGap=Math.min(minGap,distance-minimum);
        }
        double aspect=(maxX-minX)/(maxY-minY);
        require(ids.size()==599&&links.size()==647&&degrees.values().stream().filter(d->d==0).count()==335,"Actual dataset identity changed");
        require(aspect>0.70&&aspect<1.43,"Actual graph is not rounded around the origin");
        require(Math.hypot(cx/ids.size(),cy/ids.size())<20,"Actual cloud drifts from the center magnet: "+Math.hypot(cx/ids.size(),cy/ids.size()));
        require(minGap>=5.0-1e-6,"Actual graph lacks the complete 5 DIP gap");
        double initialFinalCorrection=physics.finalCorrection();
        require(initialFinalCorrection<=2.0,"Actual graph visibly snaps during final packing: "+initialFinalCorrection);
        var initialRadial=radialQuality(ids,degrees,positions);
        var initialFill=filledQuality(ids,degrees,positions,links);
        for(int i=0;i<120;i++)physics.step(1.0/60,()->false);
        require(physics.snapshot().equals(positions),"Actual settled graph changes without interaction");
        String dragged=ids.stream().max(Comparator.comparingInt(degrees::get)).orElseThrow();
        var origin=positions.get(dragged);
        physics.pin(dragged,origin.x()+100,origin.y()+60);
        for(int i=0;i<90;i++)physics.step(1.0/60,()->false);
        require(!physics.sleeping()&&physics.snapshot().get(dragged).equals(new GraphLayout.Point(origin.x()+100,origin.y()+60)),"Actual hub drag is not pinned exactly");
        physics.releaseAt(dragged,origin.x()+115,origin.y()+65);
        int releasedSteps=0;
        while(!physics.sleeping()&&releasedSteps<720){physics.step(1.0/60,()->false);releasedSteps++;}
        require(physics.sleeping(),"Actual dragged graph did not settle again within 720 steps");
        var released=physics.snapshot();double releasedGap=minimumGap(ids,degrees,released);
        require(releasedGap>=5.0-1e-6,"Actual dragged graph loses the 5 DIP gap");
        require(physics.finalCorrection()<=2.0,"Actual dragged graph visibly snaps during final packing: "+physics.finalCorrection());
        var releasedRadial=radialQuality(ids,degrees,released);
        var releasedFill=filledQuality(ids,degrees,released,links);
        for(int i=0;i<120;i++)physics.step(1.0/60,()->false);
        require(physics.snapshot().equals(released),"Actual dragged graph keeps jittering after settling");
        samples.sort(Double::compare);
        var report=new LinkedHashMap<String,Object>();
        report.put("documents",ids.size());report.put("confirmedEvidence",links.size());report.put("springs",physics.springCount());report.put("isolates",335);
        report.put("frameMedianMs",samples.get(samples.size()/2));report.put("frameP95Ms",samples.get((int)Math.ceil(samples.size()*0.95)-1));
        report.put("boundsAspect",aspect);report.put("centroidDistance",Math.hypot(cx/ids.size(),cy/ids.size()));report.put("minimumDiscSeparationRatio",minRatio);report.put("sleeping",physics.sleeping());
        report.put("minimumEdgeGapDip",minGap);report.put("initialSettleSteps",activeSteps);report.put("initialFinalCorrectionDip",initialFinalCorrection);
        report.put("draggedDocumentId",dragged);report.put("releasedSettleSteps",releasedSteps);report.put("releasedMinimumEdgeGapDip",releasedGap);report.put("releasedFinalCorrectionDip",physics.finalCorrection());
        report.put("unchangedAfterSleepSteps",120);report.put("gapUnits","world DIP, scales with camera zoom");
        report.put("initialRadialStructure",initialRadial);report.put("releasedRadialStructure",releasedRadial);
        report.put("initialLayoutMetrics",initialFill);report.put("releasedLayoutMetrics",releasedFill);
        report.put("includes","force integration, collision correction and immutable latest-frame snapshot; rendering measured separately");
        if(args.length>1)Files.writeString(Path.of(args[1]),Json.stringify(report));
        System.out.println(Json.stringify(report));
    }
    private static void require(boolean truth,String message){if(!truth)throw new AssertionError(message);}
    private static double minimumGap(List<String> ids,Map<String,Integer> degrees,Map<String,GraphLayout.Point> positions){
        double result=Double.POSITIVE_INFINITY;
        for(int a=0;a<ids.size();a++)for(int b=a+1;b<ids.size();b++){
            var p=positions.get(ids.get(a));var q=positions.get(ids.get(b));
            result=Math.min(result,Math.hypot(p.x()-q.x(),p.y()-q.y())-GraphGeometry.radius(degrees.get(ids.get(a)))-GraphGeometry.radius(degrees.get(ids.get(b))));
        }
        return result;
    }
    private static Map<String,Double> radialQuality(List<String> ids,Map<String,Integer> degrees,Map<String,GraphLayout.Point> positions){
        var connected=ids.stream().filter(id->degrees.get(id)>0).sorted(Comparator.<String>comparingInt(degrees::get).reversed().thenComparing(id->id)).toList();
        var isolated=ids.stream().filter(id->degrees.get(id)==0).map(id->radialDistance(positions.get(id))).sorted().toList();
        var connectedDistances=connected.stream().map(id->radialDistance(positions.get(id))).sorted().toList();
        var hubs=connected.subList(0,Math.min(10,connected.size())).stream().map(id->radialDistance(positions.get(id))).sorted().toList();
        var others=connected.subList(Math.min(10,connected.size()),connected.size()).stream().map(id->radialDistance(positions.get(id))).sorted().toList();
        double isolatedMedian=median(isolated),connected95=connectedDistances.get((int)Math.ceil(connectedDistances.size()*0.95)-1);
        double hubMedian=median(hubs),otherMedian=median(others);
        require(hubMedian<otherMedian,"Degree-weighted gravity does not place strong hubs preferentially near the center: "+hubMedian+" >= "+otherMedian);
        return Map.of("isolatedMedianRadiusDip",isolatedMedian,"connected95RadiusDip",connected95,"top10HubMedianRadiusDip",hubMedian,"remainingConnectedMedianRadiusDip",otherMedian,"hubToOthersMedianRatio",hubMedian/otherMedian);
    }
    private static double radialDistance(GraphLayout.Point point){return Math.hypot(point.x(),point.y());}
    private static double median(List<Double> values){return values.size()%2==0?(values.get(values.size()/2-1)+values.get(values.size()/2))/2.0:values.get(values.size()/2);}
    private static Map<String,Object> filledQuality(List<String> ids,Map<String,Integer> degrees,Map<String,GraphLayout.Point> positions,List<GraphGeometry.Link> links){
        double extent=ids.stream().mapToDouble(id->radialDistance(positions.get(id))).max().orElse(1.0);
        int[] centres=new int[16],footprints=new int[16];
        for(String id:ids){
            double distance=radialDistance(positions.get(id)),radius=GraphGeometry.radius(degrees.get(id));
            centres[Math.min(15,(int)(distance/extent*16))]++;
            for(int bin=0;bin<16;bin++)if(distance-radius<=(bin+1)*extent/16&&distance+radius>=bin*extent/16)footprints[bin]++;
        }
        require(Arrays.stream(footprints).allMatch(count->count>0),"An empty radial band remains inside the settled cloud");
        double largest=0.0;
        for(int ix=-20;ix<=20;ix++)for(int iy=-20;iy<=20;iy++){
            if(ix*ix+iy*iy>400)continue;
            double x=ix*extent*0.85/20,y=iy*extent*0.85/20,clearance=Double.POSITIVE_INFINITY;
            for(String id:ids){var point=positions.get(id);clearance=Math.min(clearance,Math.hypot(x-point.x(),y-point.y())-GraphGeometry.radius(degrees.get(id)));}
            largest=Math.max(largest,clearance);
        }
        require(largest<56.0,"A sampled interior cavity is larger than the maximum node radius: "+largest);
        var unique=new HashSet<String>();var lengths=new ArrayList<Double>();
        for(var link:links){
            String a=link.sourceId(),b=link.targetId();if(a==null||b==null||a.equals(b)||!positions.containsKey(a)||!positions.containsKey(b))continue;
            if(a.compareTo(b)>0){String swap=a;a=b;b=swap;}
            if(unique.add(a+'\0'+b)){var first=positions.get(a);var second=positions.get(b);lengths.add(Math.hypot(first.x()-second.x(),first.y()-second.y()));}
        }
        lengths.sort(Double::compare);
        var occupancy=Map.of("bins",16,"maximumCentreRadiusDip",extent,"centreCounts",Arrays.stream(centres).boxed().toList(),"discFootprintCounts",Arrays.stream(footprints).boxed().toList(),"definition","Sixteen equal radius intervals from the world origin to the furthest node centre; footprint occupancy includes each visible disc's radius");
        var empty=Map.of("largestSampledEmptyCircleRadiusDip",largest,"samples",1257,"interiorRadiusFraction",0.85,"definition","Maximum nonnegative clearance to every node-disc boundary on a 41-by-41 grid clipped to the inner 85 percent of the maximum centre-radius circle; a sample estimate, not an exact largest-empty-circle theorem");
        var length=Map.of("uniqueLines",lengths.size(),"medianDip",median(lengths),"p95Dip",lengths.get((int)Math.ceil(lengths.size()*0.95)-1),"maximumDip",lengths.getLast(),"definition","Euclidean centre-to-centre lengths of unique confirmed non-self undirected lines; no relationship or source evidence is removed");
        return Map.of("occupancy",occupancy,"emptyGap",empty,"linkLength",length);
    }
}
