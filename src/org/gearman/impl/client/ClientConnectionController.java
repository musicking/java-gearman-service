package org.gearman.impl.client;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import org.gearman.GearmanJobEventType;
import org.gearman.GearmanJobPriority;
import org.gearman.impl.GearmanConstants;
import org.gearman.impl.core.GearmanCallbackHandler;
import org.gearman.impl.core.GearmanConnection;
import org.gearman.impl.core.GearmanConnection.SendCallbackResult;
import org.gearman.impl.core.GearmanPacket;
import org.gearman.impl.server.GearmanServerInterface;
import org.gearman.impl.serverpool.AbstractConnectionController;
import org.gearman.impl.serverpool.AbstractJobServerPool;
import org.gearman.impl.util.ByteArray;
import org.gearman.impl.util.GearmanUtils;

abstract class ClientConnectionController extends AbstractConnectionController {

	private static final int RESPONCE_TIMEOUT = 19000;
	private static final int IDLE_TIMEOUT = 9000;
	
	private final InnerGearmanCallback jobSendCallback = new InnerGearmanCallback();
	
	/**
	 * The set of executing jobs. The key is the job's handle and the value is the job itself
	 */
	private final ConcurrentHashMap<ByteArray, GearmanJobReturnImpl> jobs = new ConcurrentHashMap<>();
	private ClientJobSubmission pendingJob = null;
	
	private long responceTimeout = Long.MAX_VALUE;
	private long idleTimeout = Long.MAX_VALUE;
	
	protected ClientConnectionController(AbstractJobServerPool<?> sc, GearmanServerInterface key) {
		super(sc, key);
	}
	
	public final void timeoutCheck(long time) {
		if(time-this.responceTimeout>RESPONCE_TIMEOUT) {
			super.timeout();
		} else if(this.jobs.isEmpty() && this.pendingJob==null && time-this.idleTimeout>IDLE_TIMEOUT) {
			this.closeServer();
		}
	}
	
	protected final void close() {
		if(this.pendingJob!=null) {
			this.requeueJob(pendingJob);
			this.pendingJob = null;
		}
		
		Iterator<GearmanJobReturnImpl> it = this.jobs.values().iterator();
		while(it.hasNext()) {
			GearmanJobReturnImpl jobReturn = it.next();
			it.remove();
			jobReturn.eof(GearmanJobEventImmutable.GEARMAN_JOB_DISCONNECT);
		}
		
		this.responceTimeout = Long.MAX_VALUE;
		this.idleTimeout = Long.MAX_VALUE;
	}
	
	protected abstract ClientJobSubmission pollNextJob();
	protected abstract void requeueJob(ClientJobSubmission jobSub);
	
	protected final boolean grab() {
		
		final ClientJobSubmission jobSub;
		synchronized(this) {
			if(this.pendingJob!=null) return false;
			jobSub = this.pollNextJob();
			
			if(jobSub!=null) 
				this.pendingJob = jobSub;
			else
				return false;
		}
		
		final GearmanJobPriority p = jobSub.priority;
		final String funcName = jobSub.functionName;
		final byte[] data = jobSub.data;
		final byte[] uID = jobSub.uniqueID;
		
		if(jobSub.isBackground) {
			switch(p) {
			case LOW_PRIORITY:
				this.sendPacket(GearmanPacket.createSUBMIT_JOB_LOW_BG(funcName, uID, data), jobSendCallback);
				break;
			case HIGH_PRIORITY:
				this.sendPacket(GearmanPacket.createSUBMIT_JOB_HIGH_BG(funcName, uID, data), jobSendCallback);
				break;
			case NORMAL_PRIORITY:
				this.sendPacket(GearmanPacket.createSUBMIT_JOB_BG(funcName, uID, data), jobSendCallback);
				break;
			}
		} else {
		
			switch(p) {
			case LOW_PRIORITY:
				this.sendPacket(GearmanPacket.createSUBMIT_JOB_LOW(funcName, uID, data), jobSendCallback);
				break;
			case HIGH_PRIORITY:
				this.sendPacket(GearmanPacket.createSUBMIT_JOB_HIGH(funcName, uID, data), jobSendCallback);
				break;
			case NORMAL_PRIORITY:
				this.sendPacket(GearmanPacket.createSUBMIT_JOB(funcName, uID, data), jobSendCallback);
				break;
			}
		}
		
		return true;
	}
		
	@Override
	public void onPacketReceived(GearmanPacket packet, GearmanConnection<Object> conn) {
		GearmanConstants.LOGGER.info(GearmanUtils.toString(conn) + " : IN : " + packet.getPacketType());
		
		switch (packet.getPacketType()) {
		case JOB_CREATED:
			jobCreated(packet);
			break;
		case WORK_STATUS:
			workStatus(packet);
			break;
		case WORK_COMPLETE:
			workComplete(packet);
			break;
		case WORK_FAIL:
			workFail(packet);
			break;
		case ECHO_RES:	// Not used
			assert false;
			break;
		case ERROR:
			error(packet);
			break;
		case STATUS_RES:
			super.onStatusReceived(packet);
			break;
		case WORK_EXCEPTION:
			//workException(packet);  //TODO Don't implement yet
			assert false;
			break;
		case OPTION_RES:
			//TODO
			break;
		case WORK_DATA:
			workData(packet);
			break;
		case WORK_WARNING:
			workWarning(packet);
			break;
		default: // Not a client response
			assert false;
		}
	}
	
	private final void workWarning(final GearmanPacket packet) {
		final ByteArray jobHandle = new ByteArray(packet.getArgumentData(0));
		final GearmanJobReturnImpl jobReturn = this.jobs.get(jobHandle);
		
		if(jobReturn==null) {
			GearmanConstants.LOGGER.warn("Unexspected Packet : WORK_WARNING : "+ jobHandle.toString(GearmanConstants.CHARSET));
			return;
		}
		
		final byte[] warning = packet.getArgumentData(1);
		jobReturn.put(new GearmanJobEventImpl(GearmanJobEventType.GEARMAN_JOB_WARNING, warning));
	}
	
	private final void workData(final GearmanPacket packet) {
		final ByteArray jobHandle = new ByteArray(packet.getArgumentData(0));
		final GearmanJobReturnImpl jobReturn = this.jobs.get(jobHandle);
		
		if(jobReturn==null) {
			GearmanConstants.LOGGER.warn("Unexspected Packet : WORK_DATA : "+ jobHandle.toString(GearmanConstants.CHARSET));
			return;
		}
		
		final byte[] data= packet.getArgumentData(1);
		jobReturn.put(new GearmanJobEventImpl(GearmanJobEventType.GEARMAN_JOB_DATA, data));
	}

/*
	private final void workException(final GearmanPacket packet) {
		final ByteArray jobHandle = new ByteArray(packet.getArgumentData(0));
		final GearmanJob job = this.jobs.get(jobHandle);
		
		if(job==null) {
			// TODO log warning
			return;
		}
		
		final byte[] exception = packet.getArgumentData(1);
		try {
			job.callbackException(exception);
		} catch (Throwable t) {
			// If the user throws an exception, catch it, print it, and continue.
			t.printStackTrace();
		}
	}
*/
	
	private final void workFail(final GearmanPacket packet) {
		final ByteArray jobHandle = new ByteArray(packet.getArgumentData(0));
		
		/*
		 * Note: synchronization is not needed here.
		 */
		final GearmanJobReturnImpl jobReturn = this.jobs.remove(jobHandle);
		if(this.jobs.isEmpty()) {
			this.idleTimeout = System.currentTimeMillis();
		}
		
		if(jobReturn==null) {
			GearmanConstants.LOGGER.warn("Unexspected Packet : WORK_FAIL : "+ jobHandle.toString(GearmanConstants.CHARSET));
			return;
		}
		
		jobReturn.eof(GearmanJobEventImmutable.GEARMAN_JOB_FAIL);
	}
	
	private final void jobCreated(final GearmanPacket packet) {
		final ClientJobSubmission jobSub;
		
		synchronized(this.jobs) {
			jobSub = this.pendingJob;
			this.pendingJob = null;
		}
		
		GearmanJobReturnImpl jobReturn = jobSub.jobReturn;
		
		final byte[] jobHandle = packet.getArgumentData(0);
		
		if(jobSub.isBackground) {
			jobReturn.eof(new GearmanJobEventImpl(GearmanJobEventType.GEARMAN_SUBMIT_SUCCESS, jobHandle));
		} else {
			jobReturn.put(new GearmanJobEventImpl(GearmanJobEventType.GEARMAN_SUBMIT_SUCCESS, jobHandle));
			this.jobs.put(new ByteArray(jobHandle), jobReturn);
		}
		
		this.grab();
	}
	
	private final void workStatus(final GearmanPacket packet) {
		
		final ByteArray jobHandle = new ByteArray(packet.getArgumentData(0));
		final GearmanJobReturnImpl jobReturn = ClientConnectionController.this.jobs.get(jobHandle);
		
		if(jobReturn==null) {
			GearmanConstants.LOGGER.warn("Unexspected Packet : WORK_STATUS : "+ jobHandle.toString(GearmanConstants.CHARSET));
			return;
		}
		
		byte[] numerator = packet.getArgumentData(1);
		byte[] denominator = packet.getArgumentData(2);
		byte[] data = new byte[numerator.length + denominator.length + 1];
		
		for(int i=0; i<numerator.length; i++) {
			data[i] = numerator[i];
		}
		for(int i=0; i<denominator.length; i++) {
			data[i+1+numerator.length] = denominator[i];
		}
		
		jobReturn.put(new GearmanJobEventImpl(GearmanJobEventType.GEARMAN_JOB_STATUS, data));				
	}
			
	private final void workComplete(final GearmanPacket packet) {
		final ByteArray jobHandle = new ByteArray(packet.getArgumentData(0));

		/*
		 * Note: synchronization is not needed here.
		 */
		final GearmanJobReturnImpl jobReturn = this.jobs.remove(jobHandle);
		if(this.jobs.isEmpty()) {
			this.idleTimeout = System.currentTimeMillis();
		}
		
		if(jobReturn==null) {
			GearmanConstants.LOGGER.warn("Unexspected Packet : WORK_COMPLETE : "+ jobHandle.toString(GearmanConstants.CHARSET));
			return;
		}
		
		final byte[] data = packet.getArgumentData(1);
		jobReturn.eof(new GearmanJobEventImpl(GearmanJobEventType.GEARMAN_JOB_SUCCESS, data));
	}
	
	private final void error(final GearmanPacket packet) {
		final String errorCode = new String(packet.getArgumentData(0), GearmanConstants.CHARSET);
		final String errorText = new String(packet.getArgumentData(1), GearmanConstants.CHARSET);
		
		GearmanConstants.LOGGER.error("Recived Error Packet: " + errorText + "(" + errorCode + ")");
	}
	
	private final class InnerGearmanCallback implements GearmanCallbackHandler<GearmanPacket, SendCallbackResult>  {
		@Override
		public void onComplete(GearmanPacket data, SendCallbackResult result) {
			if(!result.isSuccessful()) {
				final ClientJobSubmission jobSub;
				
				synchronized(jobs) {
					jobSub = pendingJob;
					pendingJob = null;
				}
				
				// TODO log
				jobSub.jobReturn.eof(GearmanJobEventImmutable.GEARMAN_SUBMIT_FAIL_SEND_FAILED);
				
				grab();
			}
		}
	}
}
