package org.mengyun.tcctransaction.api;

/**
 * Created by changming.xie on 1/18/17.
 */
public enum Propagation {
    // 如果存在一个事务，则支持当前事务。如果没有事务则开启
    REQUIRED(0),
    //如果存在一个事务，支持当前事务。如果没有事务，则非事务的执行
    SUPPORTS(1),
    //如果已经存在一个事务，支持当前事务。如果没有一个活动的事务，则抛出异常。
    MANDATORY(2),
    //总是开启一个新的事务。如果一个事务已经存在，则将这个存在的事务挂起。
    REQUIRES_NEW(3);

    private final int value;

    private Propagation(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }
}