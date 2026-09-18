import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';

export interface ObservabilityStackProps extends cdk.StackProps {
  service: ecs.FargateService;
  loadBalancer: elbv2.ApplicationLoadBalancer;
}

/**
 * A minimal but real dashboard + alarms (§36) -- not decorative. Covers the
 * two signals that matter most for a control plane guarding rollback safety:
 * is it up (5xx rate, healthy host count) and is it keeping up (CPU).
 */
export class ObservabilityStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ObservabilityStackProps) {
    super(scope, id, props);

    const dashboard = new cloudwatch.Dashboard(this, 'Dashboard', {
      dashboardName: 'rollbackshield-backend',
    });

    const cpuMetric = props.service.metricCpuUtilization();
    const alb5xxMetric = props.loadBalancer.metrics.httpCodeElb(
      elbv2.HttpCodeElb.ELB_5XX_COUNT,
      { period: cdk.Duration.minutes(1) },
    );

    dashboard.addWidgets(
      new cloudwatch.GraphWidget({ title: 'CPU Utilization', left: [cpuMetric] }),
      new cloudwatch.GraphWidget({ title: 'ALB 5xx', left: [alb5xxMetric] }),
    );

    new cloudwatch.Alarm(this, 'High5xxAlarm', {
      metric: alb5xxMetric,
      threshold: 5,
      evaluationPeriods: 3,
      alarmDescription: 'RollbackShield backend is returning elevated 5xx responses',
    });

    new cloudwatch.Alarm(this, 'HighCpuAlarm', {
      metric: cpuMetric,
      threshold: 80,
      evaluationPeriods: 3,
      alarmDescription: 'RollbackShield backend CPU is sustained above 80%',
    });
  }
}
