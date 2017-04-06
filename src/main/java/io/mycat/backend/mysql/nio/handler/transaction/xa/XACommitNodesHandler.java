package io.mycat.backend.mysql.nio.handler.transaction.xa;

import io.mycat.MycatServer;
import io.mycat.backend.BackendConnection;
import io.mycat.backend.mysql.nio.MySQLConnection;
import io.mycat.backend.mysql.nio.handler.transaction.AbstractCommitNodesHandler;
import io.mycat.backend.mysql.xa.CoordinatorLogEntry;
import io.mycat.backend.mysql.xa.ParticipantLogEntry;
import io.mycat.backend.mysql.xa.TxState;
import io.mycat.backend.mysql.xa.XAStateLog;
import io.mycat.config.ErrorCode;
import io.mycat.net.mysql.ErrorPacket;
import io.mycat.net.mysql.OkPacket;
import io.mycat.server.NonBlockingSession;
import io.mycat.util.StringUtil;

public class XACommitNodesHandler extends AbstractCommitNodesHandler {
	private static int COMMIT_TIMES = 5;
	private int try_commit_times = 0;
	private ParticipantLogEntry[] participantLogEntry = null;
	protected byte[] sendData = OkPacket.OK;
	public XACommitNodesHandler(NonBlockingSession session) {
		super(session);
	}
	@Override
	public void clearResources() {
		try_commit_times = 0;
		participantLogEntry = null;
		sendData = OkPacket.OK;
	}
	@Override
	protected boolean executeCommit(MySQLConnection mysqlCon, int position) {
		switch (session.getXaState()) {
		case TX_STARTED_STATE:
			if (participantLogEntry == null) {
				participantLogEntry = new ParticipantLogEntry[nodeCount];
				CoordinatorLogEntry coordinatorLogEntry = new CoordinatorLogEntry(session.getSessionXaID(), participantLogEntry, session.getXaState());
				XAStateLog.flushMemoryRepository(session.getSessionXaID(), coordinatorLogEntry);
			}
			XAStateLog.initRecoverylog(session.getSessionXaID(), position, mysqlCon);
			endPhase(mysqlCon);
			break;
		case TX_ENDED_STATE:
			if (position == 0) {
				if (!XAStateLog.saveXARecoverylog(session.getSessionXaID(), TxState.TX_PREPARING_STATE)) {
					String errMsg = "saveXARecoverylog error, the stage is TX_PREPARING_STATE";
					this.setFail(errMsg);
					sendData = makeErrorPacket(errMsg);
					nextParse();
					return false;
				}
			}
			preparePhase(mysqlCon);
			break;
		case TX_PREPARED_STATE:
			if (position == 0) {
				if (!XAStateLog.saveXARecoverylog(session.getSessionXaID(), TxState.TX_COMMITING_STATE)) {
					String errMsg = "saveXARecoverylog error, the stage is TX_COMMITING_STATE";
					this.setFail(errMsg);
					sendData = makeErrorPacket(errMsg);
					nextParse();
					return false;
				}
			}
			commitPhase(mysqlCon);
			break;
		case TX_COMMIT_FAILED_STATE:
			if (position == 0) {
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), TxState.TX_COMMIT_FAILED_STATE);
			}
			commitPhase(mysqlCon);
			break;
		default:
		}
		return true;
	}

	private byte[] makeErrorPacket(String errMsg) {
		ErrorPacket errPacket = new ErrorPacket();
		errPacket.packetId = ++packetId;
		errPacket.errno = ErrorCode.ER_UNKNOWN_ERROR;
		errPacket.message = StringUtil.encode(errMsg, session.getSource().getCharset());
		return errPacket.toBytes();
	}
	private void endPhase(MySQLConnection mysqlCon) {
		String xaTxId = mysqlCon.getConnXID(session);
		mysqlCon.execCmd("XA END " + xaTxId);
	}

	private void preparePhase(MySQLConnection mysqlCon) {
		String xaTxId = mysqlCon.getConnXID(session);
		mysqlCon.execCmd("XA PREPARE " + xaTxId);
	}

	private void commitPhase(MySQLConnection mysqlCon) {
		if (session.getXaState() == TxState.TX_COMMIT_FAILED_STATE) {
			MySQLConnection newConn = session.freshConn(mysqlCon, this);
			if (!newConn.equals(mysqlCon)) {
				mysqlCon = newConn;
			} else if (decrementCountBy(1)) {
				cleanAndFeedback();
				return;
			}
		}
		String xaTxId = mysqlCon.getConnXID(session);
		mysqlCon.execCmd("XA COMMIT " + xaTxId);
	}

	@Override
	public void okResponse(byte[] ok, BackendConnection conn) {
		MySQLConnection mysqlCon = (MySQLConnection) conn;
		switch (mysqlCon.getXaStatus()) {
		// END OK
		case TX_STARTED_STATE:
			mysqlCon.setXaStatus(TxState.TX_ENDED_STATE);
			XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
			if (decrementCountBy(1)) {
				session.setXaState(TxState.TX_ENDED_STATE);
				nextParse();
			}
			break;
		//PREPARE OK
		case TX_ENDED_STATE:
			mysqlCon.setXaStatus(TxState.TX_PREPARED_STATE);
			XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
			if (decrementCountBy(1)) {
				if(session.getXaState()== TxState.TX_ENDED_STATE){
					session.setXaState(TxState.TX_PREPARED_STATE);
				}
				nextParse();
			}
			break;
		//COMMIT OK
		case TX_COMMIT_FAILED_STATE:
		case TX_PREPARED_STATE:
			// XA reset status now
			mysqlCon.setXaStatus(TxState.TX_COMMITED_STATE);
			XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
			mysqlCon.setXaStatus(TxState.TX_INITIALIZE_STATE);
			if (decrementCountBy(1)) {
				if(session.getXaState()== TxState.TX_PREPARED_STATE){
					session.setXaState(TxState.TX_INITIALIZE_STATE);
				}
				cleanAndFeedback();
			}
			break;
		default:
			// LOGGER.error("Wrong XA status flag!");
		}
	}

	@Override
	public void errorResponse(byte[] err, BackendConnection conn) {
		ErrorPacket errPacket = new ErrorPacket();
		errPacket.read(err);
		String errmsg = new String(errPacket.message);
		this.setFail(errmsg);
		sendData = makeErrorPacket(errmsg);
		if (conn instanceof MySQLConnection) {
			MySQLConnection mysqlCon = (MySQLConnection) conn;
			switch (mysqlCon.getXaStatus()) {
			// 'xa end' error
			case TX_STARTED_STATE:
				mysqlCon.quit();
				mysqlCon.setXaStatus(TxState.TX_CONN_QUIT);
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
				if (decrementCountBy(1)) {
					session.setXaState(TxState.TX_ENDED_STATE);
					nextParse();
				}
				break;
			// 'xa prepare' error
			case TX_ENDED_STATE:
				mysqlCon.quit();
				mysqlCon.setXaStatus(TxState.TX_CONN_QUIT);
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
				if (decrementCountBy(1)) {
					if(session.getXaState()== TxState.TX_ENDED_STATE){
						session.setXaState(TxState.TX_PREPARED_STATE);
					}
					nextParse();
				}
				break;
			// 'xa commit' err
			case TX_COMMIT_FAILED_STATE:
			case TX_PREPARED_STATE:
				//TODO :服务降级？
				mysqlCon.setXaStatus(TxState.TX_COMMIT_FAILED_STATE);
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
				session.setXaState(TxState.TX_COMMIT_FAILED_STATE);
				if (decrementCountBy(1)) {
					cleanAndFeedback();
				}
				break;
			default:
				// LOGGER.error("Wrong XA status flag!");
			}
		}
	}

	@Override
	public void connectionError(Throwable e, BackendConnection conn) {
		LOGGER.warn("backend connect", e);
		String errmsg = new String(StringUtil.encode(e.getMessage(), session.getSource().getCharset()));
		this.setFail(errmsg);
		sendData = makeErrorPacket(errmsg);
		if (conn instanceof MySQLConnection) {
			MySQLConnection mysqlCon = (MySQLConnection) conn;
			switch (mysqlCon.getXaStatus()) {
			// 'xa end' connectionError
			case TX_STARTED_STATE:
				mysqlCon.quit();
				mysqlCon.setXaStatus(TxState.TX_CONN_QUIT);
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
				if (decrementCountBy(1)) {
					session.setXaState(TxState.TX_ENDED_STATE);
					nextParse();
				}
				break;
			// 'xa prepare' connectionError
			case TX_ENDED_STATE:
				mysqlCon.setXaStatus(TxState.TX_PREPARE_UNCONNECT_STATE);
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
				session.setXaState(TxState.TX_PREPARE_UNCONNECT_STATE);
				if (decrementCountBy(1)) {
					nextParse();
				}
				break;
			// 'xa commit' connectionError
			case TX_COMMIT_FAILED_STATE:
			case TX_PREPARED_STATE:
				//TODO :服务降级？
				mysqlCon.setXaStatus(TxState.TX_COMMIT_FAILED_STATE);
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
				session.setXaState(TxState.TX_COMMIT_FAILED_STATE);
				if (decrementCountBy(1)) {
					cleanAndFeedback();
				}
				break;
			default:
				// LOGGER.error("Wrong XA status flag!");
			}
		}
	}

	@Override
	public void connectionClose(BackendConnection conn, String reason) {
		this.setFail(reason);
		sendData = makeErrorPacket(reason);
		if (conn instanceof MySQLConnection) {
			MySQLConnection mysqlCon = (MySQLConnection) conn;
			switch (mysqlCon.getXaStatus()) {
			// 'xa end' connectionClose,conn has quit
			case TX_STARTED_STATE:
				mysqlCon.quit();
				mysqlCon.setXaStatus(TxState.TX_CONN_QUIT);
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
				if (decrementCountBy(1)) {
					session.setXaState(TxState.TX_ENDED_STATE);
					nextParse();
				}
				break;
			//  'xa prepare' connectionClose,conn has quit
			case TX_ENDED_STATE:
				mysqlCon.setXaStatus(TxState.TX_PREPARE_UNCONNECT_STATE);
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
				session.setXaState(TxState.TX_PREPARE_UNCONNECT_STATE);
				if (decrementCountBy(1)) {
					nextParse();
				}
				break;
				// 'xa commit' connectionClose
			case TX_COMMIT_FAILED_STATE:
			case TX_PREPARED_STATE:
				//TODO :服务降级？
				mysqlCon.setXaStatus(TxState.TX_COMMIT_FAILED_STATE);
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), mysqlCon);
				session.setXaState(TxState.TX_COMMIT_FAILED_STATE);
				if (decrementCountBy(1)) {
					cleanAndFeedback();
				}
				break;
			default:
				// LOGGER.error("Wrong XA status flag!");
			}
		}
	}

	protected void nextParse(){
		if (this.isFail()){
			session.getSource().setTxInterrupt(error);
			createErrPkg(error).write(session.getSource());
		} else {
			commit();
		}
	}
	private void cleanAndFeedback() {
		switch (session.getXaState()) {
		case TX_INITIALIZE_STATE:
			// clear all resources
			XAStateLog.saveXARecoverylog(session.getSessionXaID(), TxState.TX_COMMITED_STATE);
			//在这里释放取消限制锁
			session.cancelableStatusSet(NonBlockingSession.CANCEL_STATUS_INIT);
			byte[] send = sendData;
			session.clearResources(false);
			if (session.closed()) {
				return;
			}
			session.getSource().write(send);
			break;
		// partitionly commited,must commit again
		case TX_COMMIT_FAILED_STATE:
			MySQLConnection errConn = session.releaseExcept(TxState.TX_COMMIT_FAILED_STATE);
			if (errConn != null) {
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), session.getXaState());
				if (++try_commit_times < COMMIT_TIMES) {
					// 多试几次
					commit();
				} else {
					// 关session ,add to定时任务
					session.getSource().close("COMMIT FAILED but it will try to COMMIT repeatedly in backend until it is success!");
					MycatServer.getInstance().getXaSessionCheck().addCommitSession(session);
				}
			} else {
				XAStateLog.saveXARecoverylog(session.getSessionXaID(), TxState.TX_COMMITED_STATE);
				session.setXaState(TxState.TX_INITIALIZE_STATE);
				//在这里释放取消限制锁
				session.cancelableStatusSet(NonBlockingSession.CANCEL_STATUS_INIT);
				byte[]toSend = sendData;
				session.clearResources(false);
				if (!session.closed()) {
					session.getSource().write(toSend);
				}
			}
			break;
		// need to rollback;
		default:
			XAStateLog.saveXARecoverylog(session.getSessionXaID(), session.getXaState());
			createErrPkg(error).write(session.getSource());
			break;
		}
	}
}
