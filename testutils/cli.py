#!/usr/bin/env python3

import argparse
import binascii
import logging
import sys

from pn532 import PN532

logger = logging.getLogger()


def die(msg, exc=None):
  logger.error(msg)
  sys.exit(1)


def main():
  parser = argparse.ArgumentParser(prog='pn532')
  parser.add_argument(
      '-p',
      '--path',
      action='store',
      required=True,
      help='Path to the PN532 serial device, e.g. /dev/ttyUSB0',
  )
  parser.add_argument(
      '-f',
      '--polling-frame',
      action='store',
      help='Optional custom polling frame in hex',
  )

  args = parser.parse_args()

  polling_frame = None
  if args.polling_frame:
    try:
      polling_frame = binascii.unhexlify(args.polling_frame)
    except Exception as e:
      die('Failed to parse polling frame hex: ' + str(e))

  try:
    pn = PN532(path=args.path)

    while True:
      pn.poll_a()
      if polling_frame:
        pn.send_broadcast(polling_frame)

      pn.poll_b()
      pn.mute()
  except Exception as e:
    die('Polling failed: ' + str(e))


if __name__ == '__main__':
  main()
