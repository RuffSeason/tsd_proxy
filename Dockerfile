FROM clojure:latest

MAINTAINER aossowski <adam@porajdesign.com>

###
### OpenTSDB consumer written in clojure
### 

# Get the essentials
RUN apt-get -y update \
	&& apt-get -y upgrade \
	&& apt-get -y install vim wget \
	&& apt-get clean

# Copy over the conf file
COPY ./conf/tsd_proxy.conf /etc/tsd_proxy.conf

# Create src tree directory
RUN mkdir -p /opt
WORKDIR /opt
RUN git clone https://github.com/aravind/tsd_proxy.git
RUN rm -rf /opt/tsd_proxy/.git

# Dockerception
RUN mkdir /root/docker.info
COPY * /root/docker.info/

# Compile into jarfile
WORKDIR /opt/tsd_proxy
RUN lein uberjar

CMD ['java','-jar','/opt/tsd_proxy/target/tsd_proxy-0.2.3-standalone.jar']