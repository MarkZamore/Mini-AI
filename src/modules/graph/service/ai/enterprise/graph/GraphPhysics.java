package ai.enterprise.graph;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Center gravity, charge repulsion, springs and disc collisions; no UI or persistence. */
public final class GraphPhysics {
    private static final double CENTER=1.25, REPULSION=95_000.0, SPRING=5.0;
    private static final double DAMPING=4.5, MAX_SPEED=900.0, GAP=5.0;
    private final List<String> ids;
    private final Map<String,Integer> indexes=new HashMap<>();
    private final List<int[]> springs=new ArrayList<>();
    private final double[] x,y,vx,vy,radius;
    private final int[] degree;
    private int pinned=-1,quietSteps;
    private double pinX,pinY,heat=1.0;
    private boolean sleeping;

    public GraphPhysics(Collection<String> documentIds,Collection<GraphGeometry.Link> relationships){
        ids=documentIds.stream().distinct().sorted().toList();
        int count=ids.size();
        x=new double[count];y=new double[count];vx=new double[count];vy=new double[count];radius=new double[count];degree=new int[count];
        var degrees=GraphGeometry.uniqueDegrees(ids,relationships);
        double angle=Math.PI*(3.0-Math.sqrt(5.0));
        for(int i=0;i<count;i++){
            indexes.put(ids.get(i),i);degree[i]=degrees.get(ids.get(i));radius[i]=GraphGeometry.radius(degree[i]);
            // Every document, including an orphan, belongs to this rounded distribution.
            double distance=25.0*Math.sqrt(i+0.5);
            x[i]=Math.cos(i*angle)*distance;y[i]=Math.sin(i*angle)*distance;
        }
        var seen=new HashSet<Long>();
        for(var link:relationships){
            Integer a=indexes.get(link.sourceId()),b=indexes.get(link.targetId());
            if(a==null||b==null||a.equals(b))continue;
            int low=Math.min(a,b),high=Math.max(a,b);
            if(seen.add(((long)low<<32)|(high&0xffffffffL)))springs.add(new int[]{low,high});
        }
        sleeping=count==0;
    }
    /** The simulation owner thread applies commands using world coordinates. */
    public void pin(String id,double worldX,double worldY){
        Integer index=indexes.get(id);if(index==null)return;
        if(!Double.isFinite(worldX)||!Double.isFinite(worldY))throw new IllegalArgumentException("Non-finite pin");
        pinned=index;pinX=worldX;pinY=worldY;wake();
    }
    public void release(){pinned=-1;wake();}
    /** Preserve the last pointer position even when pin and release arrive between worker ticks. */
    public void releaseAt(String id,double worldX,double worldY){
        pin(id,worldX,worldY);
        if(pinned>=0){x[pinned]=pinX;y[pinned]=pinY;}
        release();
    }
    public void wake(){heat=1.0;quietSteps=0;sleeping=ids.isEmpty();}
    public boolean sleeping(){return sleeping;}
    public int springCount(){return springs.size();}

    public void step(double seconds,BooleanSupplier cancelled){
        if(sleeping)return;
        if(!Double.isFinite(seconds)||seconds<=0)throw new IllegalArgumentException("Invalid timestep");
        double dt=Math.min(1.0/30.0,seconds),currentHeat=1.0;
        int count=ids.size();
        double[] ax=new double[count],ay=new double[count],beforeX=x.clone(),beforeY=y.clone();
        for(int a=0;a<count;a++){
            if((a&31)==0&&cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            ax[a]-=CENTER*x[a]*currentHeat;ay[a]-=CENTER*y[a]*currentHeat;
            for(int b=a+1;b<count;b++){
                double dx=x[b]-x[a],dy=y[b]-y[a],distance=Math.sqrt(dx*dx+dy*dy);
                if(distance<0.0001){dx=((a+b)&1)==0?1.0:-1.0;dy=0.7;distance=Math.hypot(dx,dy);}
                double charge=REPULSION*currentHeat/(distance*distance+100.0);
                double overlap=radius[a]+radius[b]+GAP-distance;
                if(overlap>0)charge+=overlap*35.0;
                double fx=dx/distance*charge,fy=dy/distance*charge;
                ax[a]-=fx;ay[a]-=fy;ax[b]+=fx;ay[b]+=fy;
            }
        }
        for(int[] spring:springs){
            int a=spring[0],b=spring[1];double dx=x[b]-x[a],dy=y[b]-y[a];
            double distance=Math.max(0.001,Math.hypot(dx,dy)),rest=radius[a]+radius[b]+75.0;
            double force=SPRING*currentHeat*(distance-rest)/Math.sqrt(Math.max(1,Math.max(degree[a],degree[b])));
            double fx=dx/distance*force,fy=dy/distance*force;
            ax[a]+=fx;ay[a]+=fy;ax[b]-=fx;ay[b]-=fy;
        }
        double damping=Math.exp(-DAMPING*dt);
        for(int i=0;i<count;i++){
            if(i==pinned){vx[i]=0.0;vy[i]=0.0;x[i]=pinX;y[i]=pinY;continue;}
            vx[i]=(vx[i]+ax[i]*dt)*damping;vy[i]=(vy[i]+ay[i]*dt)*damping;
            double speed=Math.hypot(vx[i],vy[i]);if(speed>MAX_SPEED){vx[i]*=MAX_SPEED/speed;vy[i]*=MAX_SPEED/speed;}
            x[i]+=vx[i]*dt;y[i]+=vy[i]*dt;
        }
        // Bounded positional correction stops large hubs from crushing small nodes.
        for(int pass=0;pass<2;pass++)for(int a=0;a<count;a++){
            if((a&31)==0&&cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            for(int b=a+1;b<count;b++){
                double dx=x[b]-x[a],dy=y[b]-y[a],minimum=radius[a]+radius[b]+GAP,squared=dx*dx+dy*dy;
                if(squared>=minimum*minimum)continue;
                double distance=Math.sqrt(squared);if(distance<0.0001){dx=1;dy=0;distance=1;}
                double correction=(minimum-distance)*0.75;
                double aShare=a==pinned?0.0:b==pinned?1.0:0.5,bShare=b==pinned?0.0:a==pinned?1.0:0.5;
                x[a]-=dx/distance*correction*aShare;y[a]-=dy/distance*correction*aShare;
                x[b]+=dx/distance*correction*bShare;y[b]+=dy/distance*correction*bShare;
            }
        }
        double movementSquared=0,maximumMovement=0;
        for(int i=0;i<count;i++){
            double dx=x[i]-beforeX[i],dy=y[i]-beforeY[i];if(i!=pinned){vx[i]=dx/dt;vy[i]=dy/dt;}movementSquared+=dx*dx+dy*dy;maximumMovement=Math.max(maximumMovement,Math.hypot(dx,dy));
            if(!Double.isFinite(x[i])||!Double.isFinite(y[i])||!Double.isFinite(vx[i])||!Double.isFinite(vy[i]))throw new IllegalStateException("Non-finite graph simulation");
        }
        if(pinned>=0){heat=Math.max(heat,0.65);quietSteps=0;}
        else{
            heat*=Math.pow(0.992,dt*60.0);double rms=Math.sqrt(movementSquared/Math.max(1,count));
            if(heat<0.02&&rms<0.03&&maximumMovement<0.12)quietSteps++;else quietSteps=0;
            if(quietSteps>=45){sleeping=true;Arrays.fill(vx,0.0);Arrays.fill(vy,0.0);}
        }
    }
    public Map<String,GraphLayout.Point> snapshot(){var positions=new HashMap<String,GraphLayout.Point>();for(int i=0;i<ids.size();i++)positions.put(ids.get(i),new GraphLayout.Point(x[i],y[i]));return Map.copyOf(positions);}
}
