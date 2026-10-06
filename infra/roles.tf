# Three roles. The execution role is ECS's own, for starting a task: pulling its image, writing its
# logs, reading its secrets. The task role is what the running containers are; they call no AWS API,
# so all it can do is let an operator open a shell in one with ECS Exec. The auto-stop role is the
# safety net's (runtime/auto-stop.tf), and can do one thing: set a till service's task count.

data "aws_iam_policy_document" "ecs_tasks" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    # Only on behalf of this account's tasks.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "till-task-execution"
  description        = "ECS starting till's tasks: images, logs, secrets"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions = ["ssm:GetParameters"]
    resources = [
      "arn:${data.aws_partition.current.partition}:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/till/*",
    ]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "till-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

resource "aws_iam_role" "task" {
  name               = "till-task"
  description        = "till's running containers: ECS Exec, and logging in to an express Aurora cluster"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks.json
}

data "aws_iam_policy_document" "task_exec" {
  statement {
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "task_exec" {
  name   = "till-exec"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_exec.json
}

# With Aurora's express configuration a login is an IAM token, and the role that signs it must be
# allowed to connect as the user it names: postgres, on each of the two clusters (database =
# "aurora-express", docs/design/0017-aurora-express.md). Nothing at all otherwise.
data "aws_iam_policy_document" "task_database" {
  count = var.database == "aurora-express" ? 1 : 0

  statement {
    actions = ["rds-db:connect"]
    resources = [for cluster in values(var.express_clusters) :
      "arn:${data.aws_partition.current.partition}:rds-db:${var.region}:${data.aws_caller_identity.current.account_id}:dbuser:${cluster.resource_id}/postgres"
    ]
  }
}

resource "aws_iam_role_policy" "task_database" {
  count = var.database == "aurora-express" ? 1 : 0

  name   = "till-database"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_database[0].json
}

data "aws_iam_policy_document" "scheduler" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "auto_stop" {
  name               = "till-auto-stop"
  description        = "EventBridge Scheduler scaling till's services to zero when a session outlives its deadline"
  assume_role_policy = data.aws_iam_policy_document.scheduler.json
}

data "aws_iam_policy_document" "auto_stop" {
  statement {
    actions = ["ecs:UpdateService"]
    resources = [
      "arn:${data.aws_partition.current.partition}:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:service/till/*",
    ]
  }
}

resource "aws_iam_role_policy" "auto_stop" {
  name   = "till-scale-to-zero"
  role   = aws_iam_role.auto_stop.id
  policy = data.aws_iam_policy_document.auto_stop.json
}
