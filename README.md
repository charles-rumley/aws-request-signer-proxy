# AWS Request Signer Proxy

Provides a proxy service for HTTP requests to various AWS services. Under the hood, this is a Play app based on Scala, using `aws-request-signer`.

## Usage

### Docker Compose

Example of using this application with Docker Compose
    
    version: '3'
    services:
      proxy:
        image: "charlesrumley/aws-request-signer-proxy:latest"
        ports:
         - "80:9000"
        environment:
          # required
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
