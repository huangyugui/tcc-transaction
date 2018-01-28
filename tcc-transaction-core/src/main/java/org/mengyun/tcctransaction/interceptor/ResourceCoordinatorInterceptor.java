package org.mengyun.tcctransaction.interceptor;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.mengyun.tcctransaction.InvocationContext;
import org.mengyun.tcctransaction.Participant;
import org.mengyun.tcctransaction.Transaction;
import org.mengyun.tcctransaction.TransactionManager;
import org.mengyun.tcctransaction.api.Compensable;
import org.mengyun.tcctransaction.api.TransactionContext;
import org.mengyun.tcctransaction.api.TransactionStatus;
import org.mengyun.tcctransaction.api.TransactionXid;
import org.mengyun.tcctransaction.support.FactoryBuilder;
import org.mengyun.tcctransaction.utils.CompensableMethodUtils;
import org.mengyun.tcctransaction.utils.ReflectionUtils;

import java.lang.reflect.Method;

/**
 * Created by changmingxie on 11/8/15.
 */
public class ResourceCoordinatorInterceptor {

    private TransactionManager transactionManager;


    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public Object interceptTransactionContextMethod(ProceedingJoinPoint pjp) throws Throwable {

        //获取当前事务
        Transaction transaction = transactionManager.getCurrentTransaction();

        if (transaction != null) {

            //只有在Trying时才会添加参与者
            switch (transaction.getStatus()) {
                case TRYING:
                    enlistParticipant(pjp);
                    break;
                case CONFIRMING:
                    break;
                case CANCELLING:
                    break;
            }
        }

        return pjp.proceed(pjp.getArgs());
    }

    private void enlistParticipant(ProceedingJoinPoint pjp) throws IllegalAccessException, InstantiationException {

        //获取注解的方法
        Method method = CompensableMethodUtils.getCompensableMethod(pjp);
        if (method == null) {
            throw new RuntimeException(String.format("join point not found method, point is : %s", pjp.getSignature().getName()));
        }
        Compensable compensable = method.getAnnotation(Compensable.class);

        //获取提交的方法
        String confirmMethodName = compensable.confirmMethod();
        //获取取消的方法
        String cancelMethodName = compensable.cancelMethod();

        //生成该参与者的xid，
        Transaction transaction = transactionManager.getCurrentTransaction();
        TransactionXid xid = new TransactionXid(transaction.getXid().getGlobalTransactionId());

        //将TransactionContext对象 设置到参数中
        if (FactoryBuilder.factoryOf(compensable.transactionContextEditor()).getInstance().get(pjp.getTarget(), method, pjp.getArgs()) == null) {
            FactoryBuilder.factoryOf(compensable.transactionContextEditor()).getInstance().set(
                    new TransactionContext(xid, TransactionStatus.TRYING.getId()), pjp.getTarget(),
                    ((MethodSignature) pjp.getSignature()).getMethod(), pjp.getArgs());
        }

        Class targetClass = ReflectionUtils.getDeclaringType(pjp.getTarget().getClass(), method.getName(), method.getParameterTypes());

        //构建提交上下文
        InvocationContext confirmInvocation = new InvocationContext(targetClass,
                confirmMethodName,
                method.getParameterTypes(), pjp.getArgs());

        //构建取消上下文
        InvocationContext cancelInvocation = new InvocationContext(targetClass,
                cancelMethodName,
                method.getParameterTypes(), pjp.getArgs());

        // 构建参与者对像
        Participant participant =
                new Participant(
                        xid,
                        confirmInvocation,
                        cancelInvocation,
                        compensable.transactionContextEditor());
        //添加参与者
        transactionManager.enlistParticipant(participant);

    }


}
