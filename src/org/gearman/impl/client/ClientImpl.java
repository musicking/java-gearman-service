package org.gearman.impl.client;

import java.io.IOException;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.gearman.GearmanClient;
import org.gearman.GearmanJobEvent;
import org.gearman.GearmanJobPriority;
import org.gearman.GearmanJobReturn;
import org.gearman.GearmanJobStatus;
import org.gearman.GearmanLostConnectionAction;
import org.gearman.GearmanLostConnectionGrounds;
import org.gearman.GearmanLostConnectionPolicy;
import org.gearman.impl.GearmanImpl;
import org.gearman.impl.server.GearmanServerInterface;
import org.gearman.impl.serverpool.AbstractJobServerPool;
import org.gearman.impl.serverpool.ControllerState;
import org.gearman.impl.util.GearmanUtils;

public class ClientImpl extends AbstractJobServerPool<ClientImpl.InnerConnectionController> implements GearmanClient {

	protected class InnerConnectionController extends ClientConnectionController {
		
		
		protected InnerConnectionController(GearmanServerInterface key) {
			super(ClientImpl.this, key);
		}

		@Override
		protected ClientJobSubmission pollNextJob() {
			return ClientImpl.this.pollJob();
		}
		
		@Override
		protected void requeueJob(ClientJobSubmission jobSub) {
			/*
			 * Note: this method may me called by super.close()
			 */
			
			ClientImpl.this.requeueJob(jobSub);
		}
		
		@Override
		public void onLostConnection(GearmanLostConnectionPolicy policy, GearmanLostConnectionGrounds grounds) {
			super.close();
			
			if(this.getKey().isShutdown()) {
				policy.shutdownServer(this.getKey());
				super.dropServer();
				return;
			}
			
			GearmanLostConnectionAction action = null;
			
			try {
				action = policy.lostConnection(this.getKey(), grounds);
			} catch (Throwable t) {
				action = null;
			}
			
			if(action==null) {
				action = ClientImpl.this.getDefaultPolicy().lostConnection(this.getKey(), grounds);
				assert action!=null;
			}
			
			if(action.equals(GearmanLostConnectionAction.DROP)) {
				// Drop
				super.dropServer();
			} else {
				// reconnect
				
				switch(grounds) {
				case UNEXPECTED_DISCONNECT:
				case RESPONSE_TIMEOUT:
					ClientImpl.this.removeFromOpen(this);
					break;
				case FAILED_CONNECTION:
					ClientImpl.this.onFailedConnection(this);
					break;
				default:
					assert false;
				}
				
				ClientImpl.this.addController(this);
			}
		}

		@Override
		public void onNew() {
			ClientImpl.this.addController(this);
		}
		
		@Override
		public void onDrop(ControllerState oldState) {
			super.close();
			ClientImpl.this.dropController(this, oldState);
		}
		
		@Override
		public void onWait(ControllerState oldState) {
			super.close();
		}
		
		@Override
		public void onClose(ControllerState oldState) {
			super.close();
			if(oldState.equals(ControllerState.OPEN))
				ClientImpl.this.onClose(this);
		}

		@Override
		public void onConnect(ControllerState oldState) {
			super.getKey().createGearmanConnection(this, this);
		}

		@Override
		public void onOpen(ControllerState oldState) {
			ClientImpl.this.onConnectionOpen(this);
		}
	}
	
	/** The set of open connections */ 
	private final Queue<InnerConnectionController> open = new LinkedBlockingQueue<InnerConnectionController>();
	
	/** The set of available connections*/
	private final ClientConnectionList<InnerConnectionController, ClientJobSubmission> available = new ClientConnectionList<InnerConnectionController, ClientJobSubmission>();
	
	/** The set of jobs waiting to be submitted */
	private final Deque<ClientJobSubmission> jobQueue = new LinkedBlockingDeque<ClientJobSubmission>();
	
	public ClientImpl(GearmanImpl gearman) {
		super(gearman, new ClientLostConnectionPolicy(), 0L, TimeUnit.MILLISECONDS);
	}
	
	@Override
	protected InnerConnectionController createController(GearmanServerInterface key) {
		return new InnerConnectionController(key);
	}
	
	private final void addJob(ClientJobSubmission job) {
	
		InnerConnectionController conn = null;
		
		synchronized(this.open) {
			
			if(!this.open.isEmpty()) {
				this.jobQueue.addLast(job);
				
				for(InnerConnectionController icc : this.open) {
					if(icc.grab()) return;
				}
				
				final InnerConnectionController icc;
				if ((icc = this.available.tryFirst(null))!=null){
					// Make a connection
					conn = icc;
				}
				
			} else {
				
				final InnerConnectionController icc;
				if ((icc = this.available.tryFirst(job))!=null){
					// Add job to job queue
					this.jobQueue.addLast(job);
					
					// Make a connection
					conn = icc;
				} else {
					// No available servers to connect to, fail job
					job.jobReturn.put(GearmanJobEventImmutable.GEARMAN_SUBMIT_FAIL_SERVICE_SHUTDOWN);
				}
			}
		}
		
		if(conn!=null) conn.openServer(false);
	}
	
	private final void onConnectionOpen(final InnerConnectionController icc) {
		synchronized(this.open) {
			if(this.open.isEmpty())
				this.available.clearFailKeys();
			
			assert this.available.contains(icc);
			
			Object t1;
			t1 = this.available.remove(icc);
			assert t1==null && !this.available.contains(icc);
			
			this.open.add(icc);
			
			icc.grab();
		}
	}
	
	private final void addController(final InnerConnectionController icc) {
		synchronized(this.open) {
			assert (icc.getState().equals(ControllerState.CLOSED)
				|| icc.getState().equals(ControllerState.CONNECTING));
			
			this.available.add(icc);
			assert this.available.contains(icc);
		}
	}
	
	private final void dropController(final InnerConnectionController icc, final ControllerState oldState) {
		synchronized(this.open) {
			assert icc.getState().equals(ControllerState.DROPPED);
			
			switch(oldState) {
			case CONNECTING:
			case CLOSED:
				assert this.available.contains(icc);
				assert !this.open.contains(icc);
				
				final ClientJobSubmission job = this.available.remove(icc);
				if(job!=null) {
					// There should be no fail keys while there are open connections
					assert this.open.isEmpty();
					this.failTo(job, GearmanJobEventImmutable.GEARMAN_SUBMIT_FAIL_CONNECTION_FAILED);
				}
				
				break;
			case OPEN:
				assert this.open.contains(icc);
				assert !this.available.contains(icc);
				
				boolean t = this.open.remove(icc);
				assert t;
				
				break;
			case WAITING:
				assert !this.open.contains(icc);
				assert !this.available.contains(icc);
				break;
			case DROPPED:
				assert false;
				break;
			}
		}
	}
	
	/**
	 * Call when there is an expected disconnect.
	 * @param icc
	 */
	private final void onClose(final InnerConnectionController icc) {
		
		/*
		 * Move the connection controller from the open set to the available set
		 */
		
		InnerConnectionController openNext = null;
		
		synchronized(this.open) {
			
			// The controller should be in the open set
			assert this.open.contains(icc);
			
			// remove the controller from the open set
			boolean test;
			test = this.open.remove(icc);
			assert test;
			
			/*
			 * If if the set of open connections is empty and there are still jobs in the
			 * queue, attempt to make a connection
			 */
			if(this.open.isEmpty()) {
				/*
				 * Note: if the disconnect causes jobs to be added to the job queue,
				 * it should be added before this method is called 
				 */
				
				// Grab the last job added to the job queue, if one exits 
				final ClientJobSubmission job = this.jobQueue.peekLast();
				if(job!=null) {
					// If there are jobs in the jobQueue, make a new connection
					
					// try to make a new connection and set the fail key
					final InnerConnectionController conn = this.available.tryFirst(job);
					
					if(conn!=null) {
						assert conn.getState().equals(ControllerState.CLOSED)
							|| conn.getState().equals(ControllerState.CONNECTING);
						
						openNext = conn;
						
					} else {
						// If conn is null, then there are no other available connections
						this.failTo(job, GearmanJobEventImmutable.GEARMAN_SUBMIT_FAIL_SERVER_NOT_AVAILABLE);
					}
				}
			}
			
			/*
			 * since this is an expected disconnect, we know this is a good job server to
			 * connect to. Add it to the head of the list
			 */
			test= this.available.addFirst(icc);
			assert test;
		}
		
		if(openNext!=null) {
			boolean test = openNext.openServer(false);
			assert test;
		}
	}
	
	private final void onFailedConnection(final InnerConnectionController icc) {
		synchronized(this.open) {
			assert this.available.contains(icc);
			final ClientJobSubmission cjs = this.available.remove(icc);
			assert !this.available.contains(icc);
			
			if(cjs!=null) {
				// There should be no fail keys while there are open connections
				assert this.open.isEmpty();
				
				this.failTo(cjs, GearmanJobEventImmutable.GEARMAN_SUBMIT_FAIL_CONNECTION_FAILED);
			}
		}
	}
	
	private final void failTo(final ClientJobSubmission job, GearmanJobEvent failevent) {
		
		synchronized(this.open) {
			/*
			 * Note: this operation needs to be synchronized. This will prevent
			 * a connection form being established and polling the job that's
			 * being failed.
			 */
			
			assert this.open.isEmpty();
			assert this.jobQueue.contains(job);
			
			ClientJobSubmission current;
			do {
				current=this.jobQueue.pollFirst();
				current.jobReturn.eof(failevent);
			} while(current!=job);
		}
	}
	
	private final ClientJobSubmission pollJob() {
		return this.jobQueue.poll();
	}
	
	private final void requeueJob(ClientJobSubmission job) {
		this.jobQueue.addFirst(job);
	}
	
	private final void removeFromOpen(final InnerConnectionController icc) {
		synchronized(this.open) {
			assert icc.getState().equals(ControllerState.CLOSED);
			assert this.open.contains(icc);
			
			this.open.remove(icc);
		}
	}
	
	@Override
	public final void shutdown() {
		synchronized(this.open) {
			for(ClientJobSubmission jobSub : this.jobQueue){
				jobSub.jobReturn.eof(GearmanJobEventImmutable.GEARMAN_SUBMIT_FAIL_SERVICE_SHUTDOWN);
			}
			this.open.clear();
			this.available.clear();
			
			super.shutdown();
			super.getGearman().onServiceShutdown(this);
		}
	}

	@Override
	public GearmanJobStatus getStatus(byte[] jobHandle) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public GearmanJobReturn submitJob(String functionName, byte[] data) {
		return submitJob(functionName, data, GearmanJobPriority.NORMAL_PRIORITY, false);
	}

	@Override
	public GearmanJobReturn submitJob(String functionName, byte[] data, GearmanJobPriority priority) {
		return submitJob(functionName, data, priority, false);
	}

	@Override
	public GearmanJobReturn submitBackgroundJob(String functionName, byte[] data) {
		return submitJob(functionName, data, GearmanJobPriority.NORMAL_PRIORITY, true);
	}

	@Override
	public GearmanJobReturn submitBackgroundJob(String functionName, byte[] data, GearmanJobPriority priority) {
		return submitJob(functionName, data, priority, true);
	}
	
	public GearmanJobReturn submitJob(String functionName, byte[] data, GearmanJobPriority priority, boolean isBackground) {
		final GearmanJobReturnImpl jobReturn = new GearmanJobReturnImpl();
		
		if(this.isShutdown()) {
			jobReturn.eof(GearmanJobEventImmutable.GEARMAN_SUBMIT_FAIL_SERVICE_SHUTDOWN);
			return jobReturn;
		} else if (super.getServerCount()==0) {
			jobReturn.eof(GearmanJobEventImmutable.GEARMAN_SUBMIT_FAIL_SERVER_NOT_AVAILABLE);
			return jobReturn;
		}
		
		this.addJob(new ClientJobSubmission(functionName, data, GearmanUtils.createUID() , jobReturn, priority, isBackground));
		return jobReturn;
	}
}
