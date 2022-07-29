#!/usr/bin/env bash

ln -s /usr/local/julie-ops/bin/julie-ops-cli.sh /usr/bin/julie-ops
ln -s /usr/local/julie-ops/bin/julie-ops-complete.sh /etc/bash_completion.d/julie-ops-complete

chown julie-kafka.julie-kafka /usr/local/julie-ops/bin/julie-ops-cli.sh
chown julie-kafka.julie-kafka /usr/local/julie-ops/bin/julie-ops-complete.sh
