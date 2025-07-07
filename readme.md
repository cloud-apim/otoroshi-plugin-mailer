# Cloud APIM mailer otoroshi plugin

An [Otoroshi](https://github.com/MAIF/otoroshi) plugin to expose your SMTP server through a REST API to send emails

## Create a route to send emails

```shell
$ curl -X POST 'http://otoroshi-api.oto.tools:8080/api/routes' \
  -H "Content-type: application/json" \
  -u 'admin-api-apikey-id:admin-api-apikey-secret' \
  -d '{
    "name": "mailer",
    "frontend": {
      "domains": ["mailer.oto.tools/messages"]
    },
    "backend": {
      "targets": [{
        "hostname": "mirror.otoroshi.io",
        "port": 443,
        "tls": true
      }]
    },
    "plugins": [
      {
        "enabled": true,
        "plugin": "cp:otoroshi_plugins.com.cloud.apim.otoroshi.plugins.mailer.MailerEndpoint",
        "config": {
          "host": "smtp.my.host",
          "port": 465,
          "user": "foo@my.host",
          "password": "xxxx",
          "auth": true,
          "starttls_enabled": true,
          "smtps": true,
          "max_retries": 5
        }
      }
    ]
  }'
```

## Plugin configuration

the configuration of the `Mailer endpoint` plugin is the following

```javascript
{
  "host": "smtp.my.host", // the smtp server hostname
  "port": 465, // the smtp server port
  "user": "foo@my.host", // an optional username if authentication is enabled
  "password": "xxxx", // an optional password if authentication is enabled
  "auth": true, // enable authentication
  "starttls_enabled": true, // opportunistic TLS upgrade on plain text connection
  "smtps": true, // use TLS to contact the server
  "max_retries": 5 // number of retries when email sending fails
}
```

## Send emails

once you have published your route in your otoroshi instance, you can use the API

### request object

```javascript
{
  "subject": "Hello World", // the email subject
  "from": "hello@cloud-apim.com", // the email address for the sender
  "to": [], // email address list for email recipients
  "cc": [], // optional email address list for cc
  "bcc": [], // optional email address list for bcc
  "html": "<html><p>html content</p></html>", // optional html content, will infer text/html; charset=utf-8 mimetype if not defined
  "text": "text content", // optional text content, will infer text/plain; charset=utf-8 mimetype if not defined
  "content": "generic content", // optional content, will no infer mimetype. at one of html, text, content property should be present
  "mimetype": "text/plain; charset=utf-8" // optional mimetype of the content. if not defined, the plugin will try to infer one
  "attachments": [
    {
      "name": "hello.txt", // the name/filename of the attachment
      "mimetype": "text/plain", // the mimetype of the attachment
      "disposition": "...", // content disposition of the attachment. can be attachment or inline
      "content": "Y291Y291IG1hdGhpZXUgdHh0IGZpbGU" // base64 content
    }
  ]  
}
```

### request example

```shell
$ curl -X POST -H 'Content-Type: application/json' http://mailer.oto.tools:8080/messages -d '{
  "subject": "Hello World",
  "from": "hello@cloud-apim.com",
  "to": ["<your email address>"],
  "html": "<html><h1>Hello World</h1><p>Hello from <img src=\"cid:cloudapim.png\" /></p></html>",
  "attachments": [
    {
      "name": "hello.txt",
      "mimetype": "text/plain",
      "content": "SGVsbG8gZnJvbSBDbG91ZCBBUElNICE="
    },
    {
      "name":"cloudapim.png",
      "mimetype":"image/png",
      "disposition":"inline",
      "content":"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"
    }
  ]
}'

{"queued":true}
```

### responses

* status code 200 - `{ "queued": true }`: the email has been queued and will be sent soon
* status code 500 - `{ "queued": false, "error": "xxxx" }`: an error occurred, the email won't be sent

## Otoroshi events

when an email is sent, an alert will be generated on the otoroshi instance that will look like 

```json
{
  "@id" : "b132801b-2dac-4e41-a76d-1970c2898961",
  "@timestamp" : 1712925955868,
  "@type" : "AlertEvent",
  "@product" : "otoroshi",
  "@serviceId" : "--",
  "@service" : "Otoroshi",
  "@env" : "prod",
  "alert" : "EmailSendingSuccess",
  "mail" : {
    "subject" : "hello",
    "from" : "hello@cloud-api.com",
    "to" : [ "<your address>" ],
    "cc" : [ ],
    "bcc" : [ ],
    "content" : "<html><h1>Hello World</h1><p>Hello from <img src=\"cid:cloudapim.png\" /></p></html>",
    "mimetype" : "text/html; charset=utf-8",
    "config" : {
      "host": "smtp.my.host",
      "port": 465,
      "user": "foo@my.host",
      "password": "****",
      "auth": true,
      "starttls_enabled": true,
      "smtps": true,
      "max_retries": 5
    },
    "try_count" : 0,
    "attachments" : [
      {
        "name": "hello.txt",
        "mimetype": "text/plain",
        "content": "SGVsbG8gZnJvbSBDbG91ZCBBUElNICE="
      },
      {
        "name":"cloudapim.png",
        "mimetype":"image/png",
        "disposition":"inline",
        "content":"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"
      }
    ]
  }
}
```

when the plugin will fail to send an email, the following alert will be generated

```javascript
{
  "@id" : "001105f6-4a8b-4a8c-89e2-c7506ef2238d",
  "@timestamp" : 1712926157456,
  "@type" : "AlertEvent",
  "@product" : "otoroshi",
  "@serviceId" : "--",
  "@service" : "Otoroshi",
  "@env" : "prod",
  "alert" : "EmailSendingError",
  "mail" : {
    "subject" : "hello",
    "from" : "hello@cloud-api.com",
    "to" : [ "<your address>" ],
    "cc" : [ ],
    "bcc" : [ ],
    "content" : "<html><h1>Hello World</h1><p>Hello from <img src=\"cid:cloudapim.png\" /></p></html>",
    "mimetype" : "text/html; charset=utf-8",
    "config" : {
      "host": "smtp.my.host",
      "port": 465,
      "user": "foo@my.host",
      "password": "****",
      "auth": true,
      "starttls_enabled": true,
      "smtps": true,
      "max_retries": 5
    },
    "try_count" : 5,
    "attachments" : [
      {
        "name": "hello.txt",
        "mimetype": "text/plain",
        "content": "SGVsbG8gZnJvbSBDbG91ZCBBUElNICE="
      },
      {
        "name":"cloudapim.png",
        "mimetype":"image/png",
        "disposition":"inline",
        "content":"iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAABGdBTUEAALGPC/xhBQAAAAFzUkdCAK7OHOkAAAAgY0hSTQAAeiYAAICEAAD6AAAAgOgAAHUwAADqYAAAOpgAABdwnLpRPAAACZVJREFUaIHtWnlQFFca/153T/dc3TMDw6EcAgqKByioCasGNR6rUSq4HonX6mZDklq1LKvcjUajW6kcxhjLVNxoYkqyUUs8shHd6OrGLOqKroIKXnhFVBRmGOa+e7r3jwEE7GF6BsGiyt9f3d/r9/r7vf6+733ve41yp92H7gzsWSvQUTwn8KzxnMCzxnMCzxrdngDRxe9DCJLiJQlxhFKOuT18rZ699avX7eHDHrDrCMRG4zOm0GNHyjWqVp/d7eHPlLv2HbRVXneHMSzqglQCw2DuNGbudJqUoDZNXpaXEI3CX047N241WmxcSIPjSenLno6aAUCR6IM/a/MmKHC8rfaXrrrnLa49fc6lUWGJcZLkBElujqy0zGW1h8Chc50YIVi1NCInWyrYqpBjGhVedduzap1hwxYjx0HPWGLDGq2aCUGrp2ZCtBLLzZFl9qdio3CEkK6erbzuYWhs4SymnV4cB8dPOTZtM1nt3Guv0m/PUwHA6fOulR/Xi3zvUyCA4zA7n5mdT8ukbY1EJG7e8S56T8f6+H/tiiMIBAB/+bD+bLlLTN+ORiGZFH34rjZrENWRQVJTJB+t0JqtPqLJoWfl0V1BACFYsyyyg9r7kZ3RapAhA6kINd5g8gXt2CEnnjxW8WIAB+0gEIKB/UgxT4bwBeQylJUhTUuRaDU4QmC2cmNHyMPVMDjiYkXpJuqhmCh8/nRm3EtyigzTTcOATCbqXcEJTBmvWLRQLaW6TnU/nC5RCVIQAgVzVbPzaf816+Nv3vG6PXxKooShMQC4dMWtUeOJcSHYIc9DeaU7RovH9wzSq8EY3IOh/VRi+hTlG6+r/NcPa9l3Vuh3/2g98otj3yGbUoGlp5Lzl9TZHPzI4TLxBOwObsHSOpbjc7KD9BoxXJaRTukMvlpde0wCTkNyguStearm2+1Floe17IrFEcmJxKp1hr8VmieOfuzBNY/YklIn6+OHD5H260PeuOOpuOoZN0oul6Hio/bUFElmf6qswl1W4UpOlIikiiHIzqCyM6IOHbNv+tbk9QpbVEACBfNUzXkiANx/yBIE8iu9cW2U0cxRTV7xSMe+ubxOG4GrGLxwj2X9am3Vbe/XO8z908jYKPzL7ab8SUophZZ/oNeocDL0MDBlvCJai6/8xMCyAhyE14GescSLWa0CPM8D1vTquB7EwH4k0ZRd/u+Cy+Hk356vXrMsguOgpNT55IDllW6OgxWLNV98EBUqAQAYPkT61lyVYJMwgZHDZKj1TNFKzMvy/qVx7QbDnD/VOpyN88FxAAAYBhjWeOvvy7K8p+m787z/GYSFu3L+7hVlnyQB8xM2ofTUtqvgKy/Lyy65CpbrVDR2u9r7whCpvClOZw2iCAJt22lWq3AAGDa4sWn9V0ZJ0w5mUD8SADZsMYYdjjEMZubRH33R0EYuHIVm5tHRkXhLSXKiJHMABQAqBp86XlEwT+XfoGT2pzLSqWGDpQ4nr5Sj+TOYUS/I4mKJ2GgCw+C3YxS9kyRZA6khg6RJCRKEYMwIeXoqOXgAFd8j5DSsRwy++4CVb+0Iwun0N5/FpCaLDRddiQVL6+7e97aUCE+DNcSN6ZM4ccZ5+Lg9qSkW36n2frPT7G/SqPFXJyrSepNfbjcZzdzqpRHf7bFU17ArFmn8Jvf5ViOGoaVvqp8cNkKN3W094cI+da/GKygXj90HrKVlrqJiq8HoAwCjmSstczWYOFqJnTzrXLJabzRzFy+7/Un/lSrP8VOOfxy2AUBJqbP4qL28Ungz4HtiTRMmcKEynApHMx48ZK/e8EwZp+A4+PdJR7M8N0e2cknExNFyl5tvYwkA8P0+q8Ho27rD3M7ItXq2jUTYhM6Uuyw2jlGGGfOOljgwDBbMYm7c8R4rcczKo5vll6s8Fy+7ZFKUnNDKx1QMRuBo8Xt6o9nXI0ZYK129r07f9hMIq+j28HsP2sLTnufh2AmHXIbt/MHK8fytu9471Y2TjRDIKDRprGLzx9Hq1uUtUoIWvsY8rGNnTKU1AaoSLT9mMwLGsqJi67hRsl7xIceiymvuRzo2NhqvvO7mOQCAoyccwzKlAJCbI1swM2CRYvJYhZREI4bLzl8UcACHk993SGBOAxqJx8Ov/tQQap0MAErOOEkJ2vjXqG2fxXz7eUzf3uSJM04CBxwHonVtSyJB/lodRSEphTAMxr0kl0lRs7wlNm83CW6Rg5RV+iRLPlmp1Ubg7TzTBdix37ptl7BzByktNpi4YyccMVpCfBr8dGF3cBu/Nu0ptgZ6QGxhKy2FzJugyBkqjdR00deoqWWPn3L88JPNaG7PjEOuzEVq8EgNThBgGXzBlXg3uji/ucmedt2aeTFm/wzENZL0ROkaRh+P+M9YUh/tl/CIr5teRFdkKqrSmzvqpv4ofZDIXMjy37Is6A1s+3o3I+SMymD0NS6usfV25kH8DU9zk0VpNCXfS7jpQb5GAi67TWe5F3PXRtU05gU8xt233FM/TGRadKwxPZDXyjQtJOLR7Y+Yuj0BsSaUlkLOzFOaLFxhkcUWygFEqKAV2IJZjIrB9hy03bgd3KhEEaBItP59rYrGAEDZw7HycCkAOHv9itsVHVTXD9yudPaqxrPKAGDl5JwJQ5QAMGywdEbBI0+w8z9RBBga82sPAPGpHpPlJADgNmXkz+M7pHgTNCVj6if+ZBpxEgDiUof6hSoaU9OYzhCkvCWKgN7gO33e9ZuhUp6HI7vkCT8vBoDmUNNxUI96xBW+weM+ADj8sqx/ASAEp8+7gmoP4n1g1br6AX0ps8V3r4ZF0ClrmX9GDh11VFzxqBj8SpWoPYlYAhwHldfajhitxX0+MIgrYgZCpAbHcdDVPx7kXg0LNW03LoEQ/gnN72cyC2YyPA+bC037/2kDAI50AyDg2yucIB4BjziqcS7yJykX/0GNEHy311JYZAlDjTAJYAjmTqMRAoRg9hzJprivAIAjPdLqXohrd23hkaw6yZJ9zjaoAnh4Pfddf6lrzjT6+70WX+jxOUwCHA8NJi4mCgcAQz2vuNYfAHCbkr6cEbSv9shk28BKVmkFAMMAiI4HADCauDC0h44cs/btTf5xNsP6YMvfzdUPhKsYoyayA6bWVhyIPX1MeKYS44h35qsJCWzbZam6FU4u1In/SgzLlK5/X+u/XrZWX96xSkcgdGIulNb78R4oLUXUkWMY6EQCJ886/RVsu4P77zmBmvtTQef+bhMViQ/oS16p8uhFrKnhoSv+F+pUdPv9wHMCzxrdnQD/f8lQkQ9csLG/AAAAAElFTkSuQmCC"
      }
    ]
  }
}
```
