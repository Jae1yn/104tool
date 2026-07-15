package tool104.protocol;

import tool104.protocol.model.ConnectionEvent;
import tool104.protocol.model.PointUpdate;
import tool104.protocol.model.RawFrame;

/**
 * MasterSession 的事件出口。回调发生在协议线程上，实现方负责必要的线程封送。
 */
public interface SessionListener {

    void onConnectionEvent(ConnectionEvent event);

    void onPointUpdate(PointUpdate update);

    void onFrame(RawFrame frame);
}
