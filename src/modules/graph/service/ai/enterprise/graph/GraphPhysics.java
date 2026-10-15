package ai.enterprise.graph;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Continuous degree-weighted gravity, repulsion, springs and disc collisions; no persistence. */
public final class GraphPhysics {
    private static final double CENTER=8.0, GRAVITY=0.70, REPULSION=95_000.0, SPRING=7.0, PACKING=0.60;
    static final double STEP_SECONDS=1.0/60.0, MINIMUM_GAP=5.0;
    static final int MAX_SETTLE_STEPS=720;
    private static final double DAMPING=6.0, MAX_SPEED=900.0, CONTACT_EPSILON=0.000001;
    private final List<String> ids;
    private final Map<String,Integer> indexes=new HashMap<>();
    private final List<int[]> springs=new ArrayList<>();
    private final double[] x,y,vx,vy,radius,gravityStrength;
    private final int[] degree;
    private int pinned=-1,quietSteps,settleSteps;
    private double pinX,pinY,heat=1.0,finalCorrection;
    private boolean sleeping;

    public GraphPhysics(Collection<String> documentIds,Collection<GraphGeometry.Link> relationships){
        ids=documentIds.stream().distinct().sorted().toList();
        int count=ids.size();
        x=new double[count];y=new double[count];vx=new double[count];vy=new double[count];radius=new double[count];gravityStrength=new double[count];degree=new int[count];
        var degrees=GraphGeometry.uniqueDegrees(ids,relationships);
        for(int i=0;i<count;i++){
            indexes.put(ids.get(i),i);degree[i]=degrees.get(ids.get(i));radius[i]=GraphGeometry.radius(degree[i]);
            gravityStrength[i]=GRAVITY*(1.0+Math.sqrt(degree[i]));
        }
        var seen=new HashSet<Long>();
        for(var link:relationships){
            Integer a=indexes.get(link.sourceId()),b=indexes.get(link.targetId());
            if(a==null||b==null||a.equals(b))continue;
            int low=Math.min(a,b),high=Math.max(a,b);
            if(seen.add(((long)low<<32)|(high&0xffffffffL)))springs.add(new int[]{low,high});
        }
        seedOrganicDisk();
        sleeping=count==0;
    }

    /** A filled low-discrepancy disk with small deterministic jitter; no radius targets or bands. */
    private void seedOrganicDisk(){
        double area=0.0;
        for(double r:radius){double padded=r+MINIMUM_GAP/2.0;area+=padded*padded;}
        double extent=Math.sqrt(area/PACKING),goldenAngle=Math.PI*(3.0-Math.sqrt(5.0));
        int maximum=Arrays.stream(degree).max().orElse(0),threshold=Math.max(3,(int)Math.ceil(Math.sqrt(maximum)));
        var order=new ArrayList<Integer>();for(int i=0;i<ids.size();i++)order.add(i);
        // Starting hubs inside avoids having to tunnel large collision discs through a settled cloud.
        // Everyone else is interleaved: isolated documents never receive their own band or radius.
        order.sort(Comparator.<Integer>comparingInt(i->degree[i]>=threshold?0:1)
                .thenComparingInt(i->degree[i]>=threshold?-degree[i]:ids.get(i).hashCode()).thenComparing(i->ids.get(i)));
        for(int rank=0;rank<order.size();rank++){
            int i=order.get(rank);
            double distance=extent*Math.sqrt((rank+0.5)/Math.max(1,ids.size())),angle=rank*goldenAngle;
            int hash=ids.get(i).hashCode();
            double jitterX=((hash&0xffff)/65535.0-0.5)*10.0,jitterY=(((hash>>>16)&0xffff)/65535.0-0.5)*10.0;
            x[i]=Math.cos(angle)*distance+jitterX;y[i]=Math.sin(angle)*distance+jitterY;
        }
        seedConnectedNeighbors(order,threshold,goldenAngle);
        seedVacancies(order,extent,goldenAngle);
        double cx=0.0,cy=0.0;
        for(int i=0;i<ids.size();i++){cx+=x[i];cy+=y[i];}
        cx/=Math.max(1,ids.size());cy/=Math.max(1,ids.size());
        for(int i=0;i<ids.size();i++){x[i]-=cx;y[i]-=cy;}
    }

    /** Warm-start adjacent documents locally; these coordinates impose no later position constraint. */
    private void seedConnectedNeighbors(List<Integer> order,int threshold,double goldenAngle){
        var adjacent=new ArrayList<List<Integer>>();for(int i=0;i<ids.size();i++)adjacent.add(new ArrayList<>());
        for(int[] spring:springs){adjacent.get(spring[0]).add(spring[1]);adjacent.get(spring[1]).add(spring[0]);}
        var rank=Comparator.<Integer>comparingInt(i->degree[i]).reversed().thenComparing(i->ids.get(i));
        for(var neighbors:adjacent)neighbors.sort(rank);
        boolean[] placed=new boolean[ids.size()];var queue=new ArrayDeque<Integer>();
        for(int node:order)if(degree[node]>=threshold){placed[node]=true;queue.addLast(node);}
        for(int seed:order){
            if(degree[seed]==0)continue;
            if(queue.isEmpty()&&!placed[seed]){placed[seed]=true;queue.addLast(seed);}
            while(!queue.isEmpty()){
                int parent=queue.removeFirst(),childIndex=0;
                for(int child:adjacent.get(parent))if(!placed[child]){
                    double angle=childIndex*goldenAngle+(ids.get(parent).hashCode()&0xffff)/65535.0*2.0*Math.PI;
                    double distance=radius[parent]+radius[child]+MINIMUM_GAP+24.0+12.0*Math.sqrt(childIndex++);
                    x[child]=x[parent]+Math.cos(angle)*distance;y[child]=y[parent]+Math.sin(angle)*distance;
                    placed[child]=true;queue.addLast(child);
                }
            }
        }
    }

    /** Isolates fill available disc-sized pockets, without a label-dependent radius or outer belt. */
    private void seedVacancies(List<Integer> order,double extent,double goldenAngle){
        boolean[] occupied=new boolean[ids.size()];for(int i=0;i<ids.size();i++)occupied[i]=degree[i]>0;
        int candidate=0,limit=Math.max(1,ids.size()*64);
        for(int node:order)if(degree[node]==0){
            while(candidate<limit){
                int sample=candidate++;
                double distance=extent*Math.sqrt((sample+0.5)/Math.max(1,ids.size()*8)),angle=sample*goldenAngle;
                double px=Math.cos(angle)*distance,py=Math.sin(angle)*distance;boolean free=true;
                for(int other=0;other<ids.size();other++)if(occupied[other]){
                    double dx=px-x[other],dy=py-y[other],minimum=radius[node]+radius[other]+MINIMUM_GAP;
                    if(dx*dx+dy*dy<minimum*minimum){free=false;break;}
                }
                if(free){x[node]=px;y[node]=py;break;}
            }
            occupied[node]=true;
        }
    }

    /** The simulation owner thread applies commands using world coordinates. */
    public void pin(String id,double worldX,double worldY){
        Integer index=indexes.get(id);if(index==null)return;
        if(!Double.isFinite(worldX)||!Double.isFinite(worldY))throw new IllegalArgumentException("Non-finite pin");
        boolean begin=pinned!=index||sleeping;
        pinned=index;pinX=worldX;pinY=worldY;
        if(begin)wake();
    }
    public void release(){if(pinned>=0){pinned=-1;wake();}}
    /** Preserve the last pointer position even when pin and release arrive between worker ticks. */
    public void releaseAt(String id,double worldX,double worldY){
        if(!indexes.containsKey(id))return;
        pin(id,worldX,worldY);
        if(pinned>=0){x[pinned]=pinX;y[pinned]=pinY;}
        release();
    }
    public void wake(){heat=1.0;quietSteps=0;settleSteps=0;finalCorrection=0.0;sleeping=ids.isEmpty();}
    public boolean sleeping(){return sleeping;}
    public int springCount(){return springs.size();}
    /** Maximum displacement added by final packing, separate from the last physical step. */
    double finalCorrection(){return finalCorrection;}

    public void step(double seconds,BooleanSupplier cancelled){
        if(sleeping)return;
        if(!Double.isFinite(seconds)||seconds<=0)throw new IllegalArgumentException("Invalid timestep");
        double dt=Math.min(STEP_SECONDS,seconds),currentHeat=heat;
        int count=ids.size();
        double[] ax=new double[count],ay=new double[count],beforeX=x.clone(),beforeY=y.clone();
        double centerX=0.0,centerY=0.0;
        for(int i=0;i<count;i++){centerX+=x[i];centerY+=y[i];}
        centerX/=Math.max(1,count);centerY/=Math.max(1,count);
        for(int a=0;a<count;a++){
            if((a&31)==0&&cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            ax[a]-=gravityStrength[a]*x[a]*currentHeat;ay[a]-=gravityStrength[a]*y[a]*currentHeat;
            // Dragging pins a world position; recentering that held anchor would fight its neighbors.
            if(pinned<0){ax[a]-=CENTER*centerX*currentHeat;ay[a]-=CENTER*centerY*currentHeat;}
            for(int b=a+1;b<count;b++){
                double dx=x[b]-x[a],dy=y[b]-y[a],distance=Math.sqrt(dx*dx+dy*dy);
                if(distance<0.0001){dx=((a+b)&1)==0?1.0:-1.0;dy=0.7;distance=Math.hypot(dx,dy);}
                double charge=REPULSION*currentHeat/(distance*distance+100.0);
                double overlap=radius[a]+radius[b]+MINIMUM_GAP-distance;
                if(overlap>0)charge+=overlap*35.0*currentHeat;
                double fx=dx/distance*charge,fy=dy/distance*charge;
                // Centrality gives hubs more inertia against the many surrounding small discs.
                double aResponse=2.0*(degree[b]+1.0)/(degree[a]+degree[b]+2.0),bResponse=2.0-aResponse;
                ax[a]-=fx*aResponse;ay[a]-=fy*aResponse;ax[b]+=fx*bResponse;ay[b]+=fy*bResponse;
            }
        }
        for(int[] spring:springs){
            int a=spring[0],b=spring[1];double dx=x[b]-x[a],dy=y[b]-y[a];
            double distance=Math.max(0.001,Math.hypot(dx,dy));
            double rest=radius[a]+radius[b]+MINIMUM_GAP+24.0;
            double force=SPRING*currentHeat*(distance-rest)/Math.sqrt(Math.max(1,Math.min(degree[a],degree[b])));
            double fx=dx/distance*force,fy=dy/distance*force;
            double aShare=degree[b]/(double)(degree[a]+degree[b]),bShare=1.0-aShare;
            ax[a]+=fx*aShare;ay[a]+=fy*aShare;ax[b]-=fx*bShare;ay[b]-=fy*bShare;
        }
        double damping=Math.exp(-DAMPING*dt);
        for(int i=0;i<count;i++){
            if(i==pinned){vx[i]=0.0;vy[i]=0.0;x[i]=pinX;y[i]=pinY;continue;}
            vx[i]=(vx[i]+ax[i]*dt)*damping;vy[i]=(vy[i]+ay[i]*dt)*damping;
            double speed=Math.hypot(vx[i],vy[i]);if(speed>MAX_SPEED){vx[i]*=MAX_SPEED/speed;vy[i]*=MAX_SPEED/speed;}
            x[i]+=vx[i]*dt;y[i]+=vy[i]*dt;
        }
        separate(4,cancelled);
        double movementSquared=0,maximumMovement=0;
        for(int i=0;i<count;i++){
            double dx=x[i]-beforeX[i],dy=y[i]-beforeY[i];movementSquared+=dx*dx+dy*dy;maximumMovement=Math.max(maximumMovement,Math.hypot(dx,dy));
            if(!Double.isFinite(x[i])||!Double.isFinite(y[i])||!Double.isFinite(vx[i])||!Double.isFinite(vy[i]))throw new IllegalStateException("Non-finite graph simulation");
        }
        if(pinned>=0){heat=Math.max(heat,0.65);quietSteps=0;settleSteps=0;}
        else{
            heat*=Math.exp(-0.5*dt);settleSteps++;double rms=Math.sqrt(movementSquared/Math.max(1,count));
            if(heat<=0.02&&rms<=0.02&&maximumMovement<=0.08&&maximumPenetration()<=0.03)quietSteps++;else quietSteps=0;
            if(quietSteps>=30||settleSteps>=MAX_SETTLE_STEPS)finish(cancelled);
        }
    }

    private double maximumPenetration(){
        double maximum=0.0;
        for(int a=0;a<ids.size();a++)for(int b=a+1;b<ids.size();b++){
            double dx=x[b]-x[a],dy=y[b]-y[a],minimum=radius[a]+radius[b]+MINIMUM_GAP,squared=dx*dx+dy*dy;
            if(squared<minimum*minimum)maximum=Math.max(maximum,minimum-Math.sqrt(squared));
        }
        return maximum;
    }

    /** Contacts change positions and approaching normal velocity, never create new momentum. */
    private void separate(int passes,BooleanSupplier cancelled){
        for(int pass=0;pass<passes;pass++)for(int a=0;a<ids.size();a++){
            if((a&31)==0&&cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            for(int b=a+1;b<ids.size();b++){
                double dx=x[b]-x[a],dy=y[b]-y[a],minimum=radius[a]+radius[b]+MINIMUM_GAP,squared=dx*dx+dy*dy;
                if(squared>=minimum*minimum)continue;
                double distance=Math.sqrt(squared);
                double ux,uy;
                if(distance<1e-9){double angle=(a*31L+b)*2.399963229728653;ux=Math.cos(angle);uy=Math.sin(angle);}
                else{ux=dx/distance;uy=dy/distance;}
                double correction=minimum-distance+CONTACT_EPSILON;
                double aShare=a==pinned?0.0:b==pinned?1.0:(degree[b]+1.0)/(degree[a]+degree[b]+2.0),bShare=1.0-aShare;
                x[a]-=ux*correction*aShare;y[a]-=uy*correction*aShare;
                x[b]+=ux*correction*bShare;y[b]+=uy*correction*bShare;
                double approach=(vx[b]-vx[a])*ux+(vy[b]-vy[a])*uy;
                if(approach<0){vx[a]+=approach*ux*aShare;vy[a]+=approach*uy*aShare;vx[b]-=approach*ux*bShare;vy[b]-=approach*uy*bShare;}
            }
        }
    }

    /** Bounded final packing guarantees the visible gap, then the frame stays exactly unchanged. */
    private void finish(BooleanSupplier cancelled){
        double[] beforeX=x.clone(),beforeY=y.clone();
        separate(16,cancelled);
        double scale=1.0,cx=0.0,cy=0.0;
        for(int i=0;i<ids.size();i++){cx+=x[i];cy+=y[i];}
        cx/=Math.max(1,ids.size());cy/=Math.max(1,ids.size());
        for(int a=0;a<ids.size();a++){
            if((a&31)==0&&cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            for(int b=a+1;b<ids.size();b++){
                double dx=x[b]-x[a],dy=y[b]-y[a],minimum=radius[a]+radius[b]+MINIMUM_GAP+CONTACT_EPSILON,squared=dx*dx+dy*dy;
                if(squared>=minimum*minimum)continue;
                double distance=Math.sqrt(squared);
                if(distance==0.0)throw new IllegalStateException("Coincident nodes after graph packing");
                scale=Math.max(scale,minimum/distance);
            }
        }
        if(scale>1.0){scale=Math.nextUp(scale);for(int i=0;i<ids.size();i++){x[i]=cx+(x[i]-cx)*scale;y[i]=cy+(y[i]-cy)*scale;}}
        for(int i=0;i<ids.size();i++)finalCorrection=Math.max(finalCorrection,Math.hypot(x[i]-beforeX[i],y[i]-beforeY[i]));
        Arrays.fill(vx,0.0);Arrays.fill(vy,0.0);sleeping=true;
    }
    public Map<String,GraphLayout.Point> snapshot(){var positions=new HashMap<String,GraphLayout.Point>();for(int i=0;i<ids.size();i++)positions.put(ids.get(i),new GraphLayout.Point(x[i],y[i]));return Map.copyOf(positions);}
}
