package org.mengyun.tcctransaction.interceptor;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.mengyun.tcctransaction.NoExistedTransactionException;
import org.mengyun.tcctransaction.SystemException;
import org.mengyun.tcctransaction.Transaction;
import org.mengyun.tcctransaction.TransactionManager;
import org.mengyun.tcctransaction.api.Compensable;
import org.mengyun.tcctransaction.api.Propagation;
import org.mengyun.tcctransaction.api.TransactionContext;
import org.mengyun.tcctransaction.api.TransactionStatus;
import org.mengyun.tcctransaction.common.MethodType;
import org.mengyun.tcctransaction.support.FactoryBuilder;
import org.mengyun.tcctransaction.utils.CompensableMethodUtils;
import org.mengyun.tcctransaction.utils.ReflectionUtils;
import org.mengyun.tcctransaction.utils.TransactionUtils;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Created by changmingxie on 10/30/15.
 */
public class CompensableTransactionInterceptor {

    static final Logger logger = Logger.getLogger(CompensableTransactionInterceptor.class.getSimpleName());

    private TransactionManager transactionManager;

    private Set<Class<? extends Exception>> delayCancelExceptions;

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public void setDelayCancelExceptions(Set<Class<? extends Exception>> delayCancelExceptions) {
        this.delayCancelExceptions = delayCancelExceptions;
    }

    public Object interceptCompensableMethod(ProceedingJoinPoint pjp) throws Throwable {

        //获取@Compensable注解的方法，实现类的方法
        Method method = CompensableMethodUtils.getCompensableMethod(pjp);

        //获取到注解
        Compensable compensable = method.getAnnotation(Compensable.class);
        //获取到传播特性
        Propagation propagation = compensable.propagation();
        //从方法调用的参数中获取TransactionContext 对象，如果存在，则返回，如果不存在则返回null
        TransactionContext transactionContext = FactoryBuilder.factoryOf(compensable.transactionContextEditor())
                .getInstance().get(pjp.getTarget(), method, pjp.getArgs());

        boolean asyncConfirm = compensable.asyncConfirm();

        boolean asyncCancel = compensable.asyncCancel();

        //是否有存在的TCC的事务对象
        boolean isTransactionActive = transactionManager.isTransactionActive();

        if (!TransactionUtils.isLegalTransactionContext(isTransactionActive, propagation, transactionContext)) {
            throw new SystemException("no active compensable transaction while propagation is mandatory for method " + method.getName());
        }
        //获取方法的类型
        MethodType methodType = CompensableMethodUtils.calculateMethodType(propagation, isTransactionActive, transactionContext);

        switch (methodType) {
            case ROOT:
                return rootMethodProceed(pjp, asyncConfirm, asyncCancel);
            case PROVIDER:
                return providerMethodProceed(pjp, transactionContext, asyncConfirm, asyncCancel);
            default:
                return pjp.proceed();
        }
    }


    private Object rootMethodProceed(ProceedingJoinPoint pjp, boolean asyncConfirm, boolean asyncCancel) throws Throwable {

        Object returnValue = null;

        Transaction transaction = null;

        try {
            //事务开始（创建事务日志记录，并在当前线程缓存该事务日志记录）
            transaction = transactionManager.begin();

            try {
                returnValue = pjp.proceed();
            } catch (Throwable tryingException) {

                if (isDelayCancelException(tryingException)) {
                    transactionManager.syncTransaction();
                } else {
                    logger.warn(String.format("compensable transaction trying failed. transaction content:%s", JSON.toJSONString(transaction)), tryingException);

                    transactionManager.rollback(asyncCancel);
                }

                throw tryingException;
            }

            transactionManager.commit(asyncConfirm);

        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }

        return returnValue;
    }

    private Object providerMethodProceed(ProceedingJoinPoint pjp, TransactionContext transactionContext, boolean asyncConfirm, boolean asyncCancel) throws Throwable {

        Transaction transaction = null;
        try {

            switch (TransactionStatus.valueOf(transactionContext.getStatus())) {
                /**
                 * 有多个参与者，每个参与者创建一个事务日志，并且将事务缓存
                 */
                case TRYING:
                    transaction = transactionManager.propagationNewBegin(transactionContext);
                    return pjp.proceed();
                case CONFIRMING:
                    try {
                        transaction = transactionManager.propagationExistBegin(transactionContext);
                        transactionManager.commit(asyncConfirm);
                    } catch (NoExistedTransactionException excepton) {
                        //the transaction has been commit,ignore it.
                    }
                    break;
                case CANCELLING:

                    try {
                        transaction = transactionManager.propagationExistBegin(transactionContext);
                        transactionManager.rollback(asyncCancel);
                    } catch (NoExistedTransactionException exception) {
                        //the transaction has been rollback,ignore it.
                    }
                    break;
            }

        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }

        Method method = ((MethodSignature) (pjp.getSignature())).getMethod();

        return ReflectionUtils.getNullValue(method.getReturnType());
    }

    private boolean isDelayCancelException(Throwable throwable) {

        if (delayCancelExceptions != null) {
            for (Class delayCancelException : delayCancelExceptions) {

                Throwable rootCause = ExceptionUtils.getRootCause(throwable);

                if (delayCancelException.isAssignableFrom(throwable.getClass())
                        || (rootCause != null && delayCancelException.isAssignableFrom(rootCause.getClass()))) {
                    return true;
                }
            }
        }

        return false;
    }

}
