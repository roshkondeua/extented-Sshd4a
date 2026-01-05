Windows OS port forwarding from the external listenaddress to the localhost:

- 2222 being the port the sshd service on the emulator is listening on
- 2223 the port on the windows-localhost we'll forward to the emulators 2222 port.

Use "ipconfig" on the windows box to get your current address; for example: 192.168.0.56

View the current rules:

    netsh interface portproxy show all

Proxy the host interface 192.168.0.56:2223 to the localhost:2222

    netsh interface portproxy add v4tov4 listenaddress=192.168.0.56 listenport=2223 connectaddress=127.0.0.1 connectport=2222

Check/view if you have the firewall already to prevent adding multiple:

    netsh advfirewall firewall show rule "ALLOW TCP PORT 2223"

Open the firewall for port 2223 (i.e. the windows host)

    netsh advfirewall firewall add rule name="ALLOW TCP PORT 2223" dir=in action=allow protocol=TCP localport=2223

To access the emulator from a shell on the emulator hosting machine, run:

    # optional / might be needed (won't work when the emulator runs google-play services)
    adb root
    # enable a forward from the local port (i.e. 127.0.0.1) 2223 to the emulator port 2222
    adb forward tcp:2223 tcp:2222
    
Connect to the sshd server on the emulator with:

    ssh -p 2223 localhost

Connect to the sftp server on the emulator with:

    sftp -P 2223 localhost

Delete the proxy rule:

    netsh interface portproxy delete v4tov4 listenport=2223 listenaddress=192.168.0.56

Close the firewall:

    netsh advfirewall firewall delete rule name="ALLOW TCP PORT 2223"

Remove forwards:

    adb forward --remove-all
