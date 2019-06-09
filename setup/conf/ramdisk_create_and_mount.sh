#/bin/bash

###################################################
#
# file: ramdisk_create_and_mount.sh
#
# @Author:   Nikos Papakostantinou
# @Version:  02-04-2019
# @email:    nickpapac@ics.forth.gr
#
# Create Ramdisk file in Centos 7 
# Work for sith1
# WARNING: Before use it move it to /tmp
#
###################################################

create_ramDisk () {
    modprobe brd rd_nr=1 rd_size=20971520 max_part=1
    mkfs.xfs /dev/ram0
    mkdir -p /mnt/ramdisk/
    mount -t xfs /dev/ram0 /mnt/ramdisk/
    chown -R kolokasis:users /mnt/ramdisk
    chmod -R 777 /mnt/ramdisk
}

destroy_ramDisk () {
    umount /mnt/ramdisk 
    rmmod brd
}

# Print error/usage script message
usage() {
    echo
    echo "Usage:"
    echo "      $0 [-c][-d][-h]"
    echo
    echo "Options:"
    echo "      -c  Create RamDisk"
    echo "      -d  Destroy RamDisk"
    echo "      -h  Show usage"
    echo

    exit 1
}

# Check for the input arguments
while getopts "cdh" opt
do
    case "${opt}" in
        c)
            echo "Create RamDisk"
            create_ramDisk
            exit 1
            ;;
        d)
            echo "Destroy RamDisk"
            destroy_ramDisk
            exit 1
            ;;
        h)
            usage
            ;;
        *)
            usage
            ;;
    esac
done
