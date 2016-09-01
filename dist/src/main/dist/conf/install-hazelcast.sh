#!/bin/bash
#
# Script to install Hazelcast. The installation can be modified by copying it into the working directory.
#
# All properties from the 'simulator.properties' are passed as environment variables.
#
# todo:
# -
# - selecting the right os jars
# - selecting the right enterprise jars
# - use rsync instead of scp for upload
# - if downloading of maven artifacts fails, no error
# - variables to be used in quotes
# - hz-lib maven: enterprise
# - hz-lib git: enterprise
#
# done
#
# ideas
# - instead of download artifacts to local machine and then uploading; download them on the target machine
# - instead of uploading to each machine, upload to a single machine and then download from that machine

. $SIMULATOR_HOME/conf/install-hazelcast-support.sh

session_id=$1
version_spec=$2
public_ips=$3

# building of hazelcast using mvn/git if needed
prepare()
{
     if  [[ $version_spec == maven* ]] ; then
        maven_version=${version_spec#*=}

        echo Maven using $maven_version

        snapshot_repo="https://repository-hazelcast-l337.forge.cloudbees.com/snapshot"
        release_repo="https://repo1.maven.org/maven2"

        prepare_using_maven "hazelcast" "$maven_version" $release_repo $snapshot_repo
        prepare_using_maven "hazelcast-client" "$maven_version" $release_repo $snapshot_repo
    elif [[ $version_spec == git* ]] ; then
        git_branch=${version_spec#*=}

        echo Git using $git_branch

        prepare_using_git $git_branch $git_build_dir/hazelcast-os https://github.com/hazelcast/hazelcast.git
    elif [[ $version_spec == bringmyown ]] ; then
        echo Bring my own
        # we don't need to do anything.
    elif [[ $version_spec == outofthebox ]] ; then
        echo Out of the box

        jars=($(find $SIMULATOR_HOME/lib -name hazelcast*.jar))
        mkdir -p $local_install_dir/outofthebox
        for jar in "${jars[@]}" ; do
            echo "Copying file $jar"
            cp $jar $local_install_dir/outofthebox
        done
    else
        echo "Aborting, invalid VERSION_SPEC [$version_spec]"
        exit 1
    fi
}

prepare

upload
