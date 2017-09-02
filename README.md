# AWS Request Signer Proxy

This proxy allows access to AWS services requiring signed IAM HTTP requests, such as AWS Elasticsearch and AWS S3. You may use 
this project to proxy HTTP requests to any AWS service supporting [AWS Signature V4](http://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html).

Incoming HTTP requests will have a signature generated based on the content of the request and the credentials included.
The request headers will be modified to add the calculated signature, then forwarded on to the appropriate AWS service,
and the (unmodified) response will be returned.

It's fairly inconvenient to make authenticated requests to AWS services from applications that don't support AWS Sig V4.
Specifically, this proxy makes Elasticsearch clients like [Kibana](https://www.elastic.co/products/kibana) easily usable 
against an AWS Elasticsearch cluster using IAM authentication. 

Under the hood, this is a Play app on Scala, implementing [`aws-request-signer`](https://github.com/ticofab/aws-request-signer) in proxy form.

## Usage

This application requires the following parameters, they may be specified as environment variables, or added directly
to `application.conf``.

    # AWS service where authenticated requests will be sent
    PROXY_SERVICE={es, s3, ec2, sqs, ses, cloudsearch...}

    # Domain of AWS service where authenticated requests will be sent
    PROXY_SERVICE_DOMAIN=https://service-url.com

    # AWS region of AWS service
    PROXY_AWS_REGION=us-east-1

    # IAM credentials of user making requests
    # optional, will be pulled from environment if not specified
    PROXY_AWS_ACCESS_KEY=access-key
    PROXY_AWS_SECRET_KEY=secret-key

### Docker Compose

Place into a file named `docker-compose.yml`, then run `docker-compose up`.
    
    version: '3'
    services:
      proxy:
        image: "charlesrumley/aws-request-signer-proxy:latest"
        ports:
         - "80:9000"
        environment:
          # required
          - PROXY_SERVICE={es, s3, ec2, sqs, ses, cloudsearch...}
          - PROXY_SERVICE_DOMAIN=https://service-url.com/
          - PROXY_AWS_REGION=us-east-1
    
          # optional, will be pulled from environment if not specified
          - PROXY_AWS_ACCESS_KEY=access-key
          - PROXY_AWS_SECRET_KEY=secret-key 

## Dependencies

TODO

## Contributing

Push new versions of this application to Docker by calling

    sbt docker:publish 

## License

TODO
