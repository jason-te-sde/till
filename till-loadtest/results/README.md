# Load test results

One file per run of `scripts/aws.sh loadtest`: the load generator's summary (`k6`) and what
CloudWatch said about the same window (`cloudwatch`), with the commit that ran. The protocol and
what the results mean are in [`docs/load-test.md`](../../docs/load-test.md).
