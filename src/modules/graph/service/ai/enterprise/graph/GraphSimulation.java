package ai.enterprise.graph;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One daemon simulation owner; UI readers consume only the latest immutable frame. */
public final class GraphSimulation implements AutoCloseable {
    private static final long PERIOD_NANOS=Math.round(GraphPhysics.STEP_SECONDS*1_000_000_000L);
    public record Frame(long revision,Map<String,GraphLayout.Point> positions,boolean active) {}
    private record Pointer(String id,double x,double y,boolean held,long sequence) {}
    private final GraphPhysics physics;
    private final AtomicReference<Frame> latest;
    private final AtomicReference<Pointer> pointer=new AtomicReference<>();
    private final AtomicLong pointerSequence=new AtomicLong();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private final ScheduledExecutorService scheduler;
    private volatile Throwable failure;
    private Pointer applied;
    private long revision;
    private long nextTickNanos;

    public GraphSimulation(GraphPhysics physics){
        this.physics=physics;
        latest=new AtomicReference<>(new Frame(0,physics.snapshot(),!physics.sleeping()));
        scheduler=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"enterpriseai-graph-physics");thread.setDaemon(true);return thread;});
        if(!physics.sleeping())requestTick(0);
    }
    public Frame frame(){return latest.get();}
    public Throwable failure(){return failure;}
    public boolean closed(){return closed.get();}
    public boolean active(){return !closed()&&(latest.get().active()||scheduled.get());}
    public void pin(String id,double x,double y){if(!closed()){pointer.set(new Pointer(id,x,y,true,pointerSequence.incrementAndGet()));requestTick(0);}}
    public void release(String id,double x,double y){if(!closed()){pointer.set(new Pointer(id,x,y,false,pointerSequence.incrementAndGet()));requestTick(0);}}
    private void requestTick(long delayNanos){
        if(!closed()&&scheduled.compareAndSet(false,true)){
            try{scheduler.schedule(this::tick,delayNanos,TimeUnit.NANOSECONDS);}
            catch(RejectedExecutionException error){scheduled.set(false);if(!closed())throw error;}
        }
    }

    private void tick(){
        if(closed())return;
        try{
            // Keep the active series at 60 Hz; an idle graph starts a fresh epoch.
            if(nextTickNanos==0L||physics.sleeping())nextTickNanos=System.nanoTime();
            Pointer requested=pointer.get();
            if(requested!=applied){
                if(requested!=null){
                    if(requested.held)physics.pin(requested.id,requested.x,requested.y);
                    else physics.releaseAt(requested.id,requested.x,requested.y);
                }
                applied=requested;
            }
            if(physics.sleeping())return;
            physics.step(GraphPhysics.STEP_SECONDS,closed::get);
            if(!closed())latest.set(new Frame(++revision,physics.snapshot(),!physics.sleeping()));
        }catch(java.util.concurrent.CancellationException ignored){}
        catch(Throwable error){failure=error;close();}
        finally{
            scheduled.set(false);
            if(!closed()&&(!physics.sleeping()||pointer.get()!=applied)){
                nextTickNanos+=PERIOD_NANOS;
                requestTick(Math.max(0L,nextTickNanos-System.nanoTime()));
            }else nextTickNanos=0L;
        }
    }
    public void close(){if(closed.compareAndSet(false,true))scheduler.shutdownNow();}
    /** Used by tests/hosts that need confirmation that no worker remains. Never called by UI. */
    public boolean awaitStopped(long milliseconds)throws InterruptedException{return scheduler.awaitTermination(milliseconds,TimeUnit.MILLISECONDS);}
}
