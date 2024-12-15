import socket
import hashlib
import sys

def get_file_list(server_ip, server_port):
    # Request file list
    request_type = 1
    request = bytearray([request_type, 0, 0, 0, 0, 0, 0, 0, 0, 0])
    
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.sendto(request, (server_ip, server_port))
        response, _ = sock.recvfrom(1024)
        
    file_count = response[1]
    files = []
    offset = 2
    for _ in range(file_count):
        file_id = response[offset]
        file_name = response[offset + 1:].split(b'\x00', 1)[0].decode()
        files.append((file_id, file_name))
        offset += len(file_name) + 2

    return files

def get_file_size(server_ip, server_port, file_id):
    # Request file size
    request_type = 2
    request = bytearray([request_type, file_id, 0, 0, 0, 0, 0, 0, 0, 0])
    
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.sendto(request, (server_ip, server_port))
        response, _ = sock.recvfrom(1024)
        
    file_size = int.from_bytes(response[4:8], byteorder='big')
    return file_size

def download_file(server_ip1, server_port1, server_ip2, server_port2, file_id, file_size):
    # Download file using two network interfaces
    request_type = 3
    file_data = bytearray(file_size)

    def download_part(server_ip, server_port, start_byte, end_byte):
        request = bytearray([request_type, file_id]) + start_byte.to_bytes(4, byteorder='big') + end_byte.to_bytes(4, byteorder='big')
        
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.sendto(request, (server_ip, server_port))
            response, _ = sock.recvfrom(1024)
            
        start_byte = int.from_bytes(response[2:6], byteorder='big')
        end_byte = int.from_bytes(response[6:10], byteorder='big')
        file_data[start_byte:end_byte + 1] = response[10:]
    
    mid_point = file_size // 2
    download_part(server_ip1, server_port1, 0, mid_point)
    download_part(server_ip2, server_port2, mid_point + 1, file_size - 1)
    
    with open(f"file_{file_id}.dat", "wb") as file:
        file.write(file_data)

def verify_md5(file_path, expected_md5):
    # Verify file integrity using MD5
    hash_md5 = hashlib.md5()
    with open(file_path, "rb") as file:
        for chunk in iter(lambda: file.read(4096), b""):
            hash_md5.update(chunk)
    return hash_md5.hexdigest() == expected_md5

def main():
    if len(sys.argv) < 3:
        print("Usage: python client.py server_ip1:port1 server_ip2:port2")
        return
    
    server_ip1, server_port1 = sys.argv[1].split(':')
    server_ip2, server_port2 = sys.argv[2].split(':')

    # Request file list
    file_list = get_file_list(server_ip1, int(server_port1))
    print("Available files:", file_list)

    # Request file size for selected file
    file_id = int(input("Enter file ID to download: "))
    file_size = get_file_size(server_ip1, int(server_port1), file_id)
    print(f"File size: {file_size} bytes")

    # Download file
    download_file(server_ip1, int(server_port1), server_ip2, int(server_port2), file_id, file_size)

    # Verify file integrity
    file_path = f"file_{file_id}.dat"
    expected_md5 = input("Enter expected MD5 hash: ")
    if verify_md5(file_path, expected_md5):
        print("File integrity verified successfully.")
    else:
        print("File integrity verification failed.")

if __name__ == "__main__":
    main()
