package main

// DPAPI(Windows 数据保护 API)加密令牌,绑定当前用户。
// 配置文件里只存 CryptProtectData 的密文,不出现明文令牌。

import (
	"encoding/base64"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	modcrypt32  = windows.NewLazySystemDLL("crypt32.dll")
	pCryptProtect   = modcrypt32.NewProc("CryptProtectData")
	pCryptUnprotect = modcrypt32.NewProc("CryptUnprotectData")
	pLocalFree      = modkernel32.NewProc("LocalFree")
)

// dataBlob 对应 C 的 DATA_BLOB {DWORD cbData; BYTE *pData},顺序不能反。
type dataBlob struct {
	length uint32
	data   *byte
}

func blobToBytes(b dataBlob) []byte {
	if b.data == nil || b.length == 0 {
		return nil
	}
	return unsafe.Slice(b.data, b.length)
}

func dpapiProtect(plain []byte) (string, error) {
	if len(plain) == 0 {
		return "", nil
	}
	in := dataBlob{length: uint32(len(plain)), data: &plain[0]}
	var out dataBlob
	// CryptProtectData(pDataIn, szDataDescr, pOptionalEntropy, pvReserved, pPromptStruct, dwFlags, pDataOut)
	r, _, err := pCryptProtect.Call(
		uintptr(unsafe.Pointer(&in)),
		0, 0, 0, 0,
		0, // dwFlags
		uintptr(unsafe.Pointer(&out)),
	)
	if r == 0 {
		return "", err
	}
	defer pLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	return base64.StdEncoding.EncodeToString(blobToBytes(out)), nil
}

func dpapiUnprotect(enc string) ([]byte, error) {
	if enc == "" {
		return nil, nil
	}
	raw, err := base64.StdEncoding.DecodeString(enc)
	if err != nil {
		return nil, err
	}
	in := dataBlob{data: &raw[0], length: uint32(len(raw))}
	var out dataBlob
	// CryptUnprotectData(pDataIn, ppszDataDescr, pOptionalEntropy, pvReserved, pPromptStruct, dwFlags, pDataOut)
	r, _, err := pCryptUnprotect.Call(
		uintptr(unsafe.Pointer(&in)),
		0, 0, 0, 0,
		0, // dwFlags
		uintptr(unsafe.Pointer(&out)),
	)
	if r == 0 {
		return nil, err
	}
	defer pLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	outBytes := append([]byte{}, blobToBytes(out)...)
	return outBytes, nil
}
